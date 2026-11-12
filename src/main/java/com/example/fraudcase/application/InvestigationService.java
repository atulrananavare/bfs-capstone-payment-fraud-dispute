package com.example.fraudcase.application;

import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.agent.common.RecommendationValidator;
import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.agent.springai.InvalidModelOutputException;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.ClaimFacts;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.PolicyPassage;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.persistence.EvidenceItemEntity;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.FraudCaseStore;
import com.example.fraudcase.persistence.PolicyCitationEntity;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.persistence.RecommendationRepository;
import com.example.fraudcase.persistence.ToolTraceEntity;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.rag.PolicyQueryBuilder;
import com.example.fraudcase.rag.PolicyRetrievalOutcome;
import com.example.fraudcase.rag.PolicyRetriever;
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.Determinism;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.ToolResult;
import com.example.fraudcase.tools.TransactionDetailsTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bounded deterministic investigation flow:
 * <pre>
 * NEW/INCOMPLETE -&gt; ENRICHING
 *   -&gt; allow-listed tools (fixed order, traced, audited)
 *   -&gt; EvidenceItems (idempotent, content-derived ids)
 *   -&gt; FraudCaseProcessor (rules mode)
 *   -&gt; SLA tool for the proposed route
 *   -&gt; RecommendationValidator
 *   -&gt; persist recommendation (never invalid ones)
 *   -&gt; READY_FOR_REVIEW, or INCOMPLETE for insufficient/contradictory evidence
 * </pre>
 * All state transitions are owned here — processors never touch state.
 *
 * <p><b>Restart semantics:</b> each run gets a new investigation id and new
 * tool traces (operational records). Evidence ids are content-derived, so
 * identical facts are idempotent and never duplicated. The newest
 * recommendation supersedes earlier ones via createdAt ordering.
 */
@Service
public class InvestigationService {

    /** Deterministic last-resort marker for the manual-review stub. */
    static final String LAST_RESORT_RULE_ID = "R000";

    private static final int AUDIT_REASON_MAX = 200;

    private final FraudCaseStore caseStore;
    private final EvidenceItemRepository evidenceRepository;
    private final ToolTraceRepository traceRepository;
    private final RecommendationRepository recommendationRepository;
    private final PolicyCitationRepository citationRepository;
    private final ToolExecutionService toolExecution;
    private final EvidenceRecorder evidenceRecorder;
    private final AuditService auditService;
    private final RulesFraudCaseAgent rulesAgent;
    private final ProcessorSelector processorSelector;
    private final RecommendationValidator recommendationValidator;
    private final CapstoneProperties properties;
    private final Clock clock;

    private final TransactionDetailsTool transactionTool;
    private final DeviceSignalsTool deviceTool;
    private final CompareRecentLocationsTool locationTool;
    private final MerchantRiskProfileTool merchantTool;
    private final PriorDisputesTool priorDisputesTool;
    private final CaseSlaTool slaTool;
    private final ObjectProvider<PolicyRetriever> policyRetriever;

    public InvestigationService(
            FraudCaseStore caseStore,
            EvidenceItemRepository evidenceRepository,
            ToolTraceRepository traceRepository,
            RecommendationRepository recommendationRepository,
            PolicyCitationRepository citationRepository,
            ToolExecutionService toolExecution,
            EvidenceRecorder evidenceRecorder,
            AuditService auditService,
            RulesFraudCaseAgent rulesAgent,
            ProcessorSelector processorSelector,
            RecommendationValidator recommendationValidator,
            CapstoneProperties properties,
            Clock clock,
            TransactionDetailsTool transactionTool,
            DeviceSignalsTool deviceTool,
            CompareRecentLocationsTool locationTool,
            MerchantRiskProfileTool merchantTool,
            PriorDisputesTool priorDisputesTool,
            CaseSlaTool slaTool,
            ObjectProvider<PolicyRetriever> policyRetriever) {
        this.caseStore = caseStore;
        this.evidenceRepository = evidenceRepository;
        this.traceRepository = traceRepository;
        this.recommendationRepository = recommendationRepository;
        this.citationRepository = citationRepository;
        this.toolExecution = toolExecution;
        this.evidenceRecorder = evidenceRecorder;
        this.auditService = auditService;
        this.rulesAgent = rulesAgent;
        this.processorSelector = processorSelector;
        this.recommendationValidator = recommendationValidator;
        this.properties = properties;
        this.clock = clock;
        this.transactionTool = transactionTool;
        this.deviceTool = deviceTool;
        this.locationTool = locationTool;
        this.merchantTool = merchantTool;
        this.priorDisputesTool = priorDisputesTool;
        this.slaTool = slaTool;
        this.policyRetriever = policyRetriever;
    }

    public record InvestigationResult(
            String investigationId,
            FraudCase fraudCase,
            FraudCaseRecommendation recommendation,
            RecommendationMode mode,
            int toolsExecuted,
            int evidenceCount,
            List<ToolOutcome> toolOutcomes) {
    }

    @Transactional(noRollbackFor = RecommendationValidationException.class)
    public InvestigationResult investigate(String caseId) {
        FraudCase fraudCase = caseStore.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Application-owned state transition into ENRICHING.
        switch (fraudCase.state()) {
            case NEW, INCOMPLETE -> {
                fraudCase.transitionTo(CaseState.ENRICHING);
                caseStore.save(fraudCase);
                auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                        ActorType.SYSTEM, "ENRICHING");
            }
            case ENRICHING -> {
                // resume of an interrupted investigation — no transition
            }
            default -> throw new InvestigationNotPermittedException(caseId, fraudCase.state());
        }

        String investigationId = "INV-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        auditService.record(caseId, AuditEventType.INVESTIGATION_STARTED,
                ActorType.SYSTEM, investigationId);

        // Customer intake is evidence too — clearly marked as a CLAIM, never
        // presented as independently observed fact.
        persistEvidence(claimEvidence(fraudCase), investigationId);

        // ---- ACT: bounded evidence collection, fixed allow-listed order ----
        List<ToolOutcome> outcomes = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        List<EvidenceItem> runEvidence = new ArrayList<>();

        collect(toolExecution.run(transactionTool,
                new TransactionDetailsTool.Request(fraudCase.transaction().transactionRef()),
                caseId, investigationId), caseId, outcomes, traceIds, runEvidence);
        collect(toolExecution.run(deviceTool,
                new DeviceSignalsTool.Request(fraudCase.customer().maskedCustomerRef()),
                caseId, investigationId), caseId, outcomes, traceIds, runEvidence);
        collect(toolExecution.run(locationTool,
                new CompareRecentLocationsTool.Request(fraudCase.customer().maskedCustomerRef()),
                caseId, investigationId), caseId, outcomes, traceIds, runEvidence);
        collect(toolExecution.run(merchantTool,
                new MerchantRiskProfileTool.Request(fraudCase.transaction().maskedMerchant()),
                caseId, investigationId), caseId, outcomes, traceIds, runEvidence);
        collect(toolExecution.run(priorDisputesTool,
                new PriorDisputesTool.Request(fraudCase.customer().maskedCustomerRef()),
                caseId, investigationId), caseId, outcomes, traceIds, runEvidence);

        runEvidence.forEach(e -> persistEvidence(e, investigationId));

        // ---- ASSESS + RECOMMEND ----
        List<EvidenceItem> allEvidence = evidenceFor(caseId);
        FraudCaseContext context = new FraudCaseContext(
                fraudCase, allEvidence, outcomes, traceIds, investigationId);

        CapstoneProperties.ProcessorMode processorMode = properties.getProcessorMode();
        RecommendationMode mode = processorMode == CapstoneProperties.ProcessorMode.SPRING_AI
                ? RecommendationMode.SPRING_AI : RecommendationMode.RULES;

        // Policy grounding is attempted only for the spring-ai processor —
        // the rules path must never depend on retrieval infrastructure.
        if (mode == RecommendationMode.SPRING_AI) {
            context = retrievePolicy(context, caseId, fraudCase, outcomes);
        }

        FraudCaseRecommendation draft;
        if (mode == RecommendationMode.SPRING_AI) {
            FraudCaseProcessor processor = processorSelector.processor();
            try {
                draft = processor.analyze(context);
            } catch (InvalidModelOutputException ex) {
                auditService.record(caseId, AuditEventType.MODEL_OUTPUT_REJECTED,
                        ActorType.SYSTEM, bounded(ex.getClass().getSimpleName()));
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (msg.contains("citation") || msg.contains("retrieved")) {
                    auditService.record(caseId, AuditEventType.POLICY_CITATION_REJECTED,
                            ActorType.SYSTEM, bounded(ex.getMessage()));
                }
                draft = null;
            } catch (RuntimeException ex) {
                auditService.record(caseId, AuditEventType.MODEL_INVOCATION_FAILED,
                        ActorType.SYSTEM, bounded(ex.getClass().getSimpleName()));
                draft = null;
            }
        } else {
            draft = rulesAgent.analyze(context);
        }

        FraudCaseRecommendation recommendation = null;
        List<String> violations = null;
        if (draft != null) {
            FraudCaseRecommendation merged = withSlaEvidence(draft, fraudCase,
                    caseId, investigationId, outcomes, traceIds);
            allEvidence = evidenceFor(caseId);
            violations = recommendationValidator.validate(
                    merged, caseId, evidenceIdsOf(allEvidence),
                    caseTraceIds(caseId), mode, knownRuleIdsFor(mode),
                    allowedCitationsFor(context), context.policyStatus());
            if (violations.isEmpty()) {
                recommendation = merged;
            } else if (mode == RecommendationMode.SPRING_AI) {
                auditService.record(caseId, AuditEventType.MODEL_OUTPUT_REJECTED,
                        ActorType.SYSTEM, bounded(String.join("; ", violations)));
                if (mentionsCitation(merged, violations)) {
                    auditService.record(caseId, AuditEventType.POLICY_CITATION_REJECTED,
                            ActorType.SYSTEM, bounded(String.join("; ", violations)));
                }
            } else {
                auditService.record(caseId, AuditEventType.RECOMMENDATION_REJECTED,
                        ActorType.SYSTEM, bounded(String.join("; ", violations)));
                fraudCase.transitionTo(CaseState.INCOMPLETE);
                caseStore.save(fraudCase);
                auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                        ActorType.SYSTEM, "INCOMPLETE");
                throw new RecommendationValidationException(violations);
            }
        }

        // ---- Safe deterministic fallback (spring-ai failures/rejections) ----
        if (recommendation == null && mode == RecommendationMode.SPRING_AI) {
            auditService.record(caseId, AuditEventType.RULES_FALLBACK_STARTED,
                    ActorType.SYSTEM, "spring-ai unavailable or rejected");
            mode = RecommendationMode.RULES_FALLBACK;
            FraudCaseRecommendation fallback = rulesAgent.analyze(context);
            FraudCaseRecommendation merged = withSlaEvidence(fallback, fraudCase,
                    caseId, investigationId, outcomes, traceIds);
            allEvidence = evidenceFor(caseId);
            violations = recommendationValidator.validate(
                    merged, caseId, evidenceIdsOf(allEvidence),
                    caseTraceIds(caseId), mode, knownRuleIdsFor(mode),
                    allowedCitationsFor(context), context.policyStatus());
            if (!violations.isEmpty()) {
                // Should be unreachable for the deterministic agent — apply
                // the fixed manual-review stub rather than persist anything
                // questionable.
                recommendation = lastResort(caseId, traceIds);
                violations = recommendationValidator.validate(
                        recommendation, caseId, evidenceIdsOf(allEvidence),
                        caseTraceIds(caseId), mode, knownRuleIdsFor(mode));
                if (!violations.isEmpty()) {
                    auditService.record(caseId, AuditEventType.RECOMMENDATION_REJECTED,
                            ActorType.SYSTEM, bounded(String.join("; ", violations)));
                    fraudCase.transitionTo(CaseState.INCOMPLETE);
                    caseStore.save(fraudCase);
                    auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                            ActorType.SYSTEM, "INCOMPLETE");
                    throw new RecommendationValidationException(violations);
                }
            } else {
                recommendation = merged;
            }
            auditService.record(caseId, AuditEventType.RULES_FALLBACK_COMPLETED,
                    ActorType.SYSTEM, "mode=RULES_FALLBACK");
        }

        String recommendationId = "REC-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        RecommendationEntity entity = new RecommendationEntity(
                recommendationId, caseId, investigationId,
                mode,
                recommendation.classification(),
                recommendation.recommendedRoute(),
                recommendation.riskLevel(),
                recommendation.confidence(),
                recommendation.humanReviewRequired(),
                recommendation.rationale(),
                java.time.OffsetDateTime.now(clock));
        entity.setEvidenceIds(recommendation.evidenceIds());
        entity.setRuleIds(recommendation.ruleIds());
        entity.setToolTraceIds(recommendation.toolTraceIds());
        entity.setMissingInformation(recommendation.missingInformation());
        recommendationRepository.save(entity);
        recommendation.policyCitations().forEach(c -> citationRepository.save(
                new PolicyCitationEntity(
                        "CIT-" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 12).toUpperCase(),
                        recommendationId, c.documentId(), c.version(), c.section(),
                        c.chunkId(), c.score(), c.title(),
                        c.effectiveDate() == null ? null
                                : java.time.LocalDate.parse(c.effectiveDate()),
                        c.passage())));
        if (!recommendation.policyCitations().isEmpty()) {
            auditService.record(caseId, AuditEventType.POLICY_CITATION_ATTACHED,
                    ActorType.SYSTEM, "count=" + recommendation.policyCitations().size()
                            + "|" + recommendationId);
        }
        auditService.record(caseId, AuditEventType.RECOMMENDATION_CREATED,
                ActorType.SYSTEM, recommendationId + "|" + recommendation.classification()
                        + "|" + recommendation.recommendedRoute() + "|" + recommendation.ruleIds());

        // ---- Controlled transition ----
        CaseState target = recommendation.classification() == CaseClassification.INSUFFICIENT_EVIDENCE
                ? CaseState.INCOMPLETE : CaseState.READY_FOR_REVIEW;
        fraudCase.transitionTo(target);
        caseStore.save(fraudCase);
        auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                ActorType.SYSTEM, target.name());

        return new InvestigationResult(investigationId, fraudCase, recommendation,
                mode, outcomes.size(), allEvidence.size(), List.copyOf(outcomes));
    }

    /**
     * Policy grounding step. Runs the allow-listed read-only
     * {@code retrievePolicyEvidence} capability and carries the outcome on
     * the context: passages when retrieved, explicit NO_MATCH/UNAVAILABLE
     * otherwise. Retrieval failure is never fatal — it becomes an
     * abstention that drives safe manual-review handling downstream.
     */
    private FraudCaseContext retrievePolicy(
            FraudCaseContext context, String caseId,
            FraudCase fraudCase, List<ToolOutcome> outcomes) {
        PolicyRetriever retriever = policyRetriever.getIfAvailable();
        if (retriever == null) {
            auditService.record(caseId, AuditEventType.POLICY_RETRIEVAL_FAILED,
                    ActorType.SYSTEM, "retriever bean unavailable");
            return context.withPolicy(List.of(),
                    PolicyContextStatus.UNAVAILABLE);
        }
        auditService.record(caseId, AuditEventType.POLICY_RETRIEVAL_STARTED,
                ActorType.SYSTEM, context.investigationId());
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                PolicyQueryBuilder.fromContext(fraudCase, outcomes));
        return switch (outcome.status()) {
            case RETRIEVED -> {
                auditService.record(caseId,
                        AuditEventType.POLICY_RETRIEVAL_COMPLETED,
                        ActorType.SYSTEM, "passages=" + outcome.passages().size()
                                + "|" + outcome.querySummary());
                yield context.withPolicy(outcome.passages(),
                        PolicyContextStatus.RETRIEVED);
            }
            case NO_MATCH -> {
                auditService.record(caseId,
                        AuditEventType.POLICY_RETRIEVAL_NO_MATCH,
                        ActorType.SYSTEM, outcome.querySummary());
                yield context.withPolicy(List.of(),
                        PolicyContextStatus.NO_MATCH);
            }
            case UNAVAILABLE -> {
                auditService.record(caseId,
                        AuditEventType.POLICY_RETRIEVAL_FAILED,
                        ActorType.SYSTEM, bounded(outcome.failureReason() == null
                                ? "unknown" : outcome.failureReason()));
                yield context.withPolicy(List.of(),
                        PolicyContextStatus.UNAVAILABLE);
            }
        };
    }

    /** The only citations a recommendation may legitimately carry. */
    private static Set<PolicyCitation> allowedCitationsFor(
            FraudCaseContext context) {
        return context.policyPassages().stream()
                .map(PolicyCitation::from)
                .collect(Collectors.toSet());
    }

    private static boolean mentionsCitation(FraudCaseRecommendation draft,
                                            List<String> violations) {
        if (!draft.policyCitations().isEmpty()) {
            return true;
        }
        return violations.stream().anyMatch(v -> v.toLowerCase()
                .contains("citation") || v.toLowerCase().contains("policy"));
    }

    /**
     * Runs the SLA tool for the draft's proposed route and returns the
     * recommendation enriched with SLA evidence/trace ids plus every trace
     * produced for this run.
     */
    private FraudCaseRecommendation withSlaEvidence(
            FraudCaseRecommendation draft, FraudCase fraudCase, String caseId,
            String investigationId, List<ToolOutcome> outcomes, List<String> traceIds) {
        ToolExecutionService.Execution slaExecution = toolExecution.run(slaTool,
                new CaseSlaTool.Request(
                        draft.recommendedRoute(), draft.riskLevel(), fraudCase.reportedAt()),
                caseId, investigationId);
        outcomes.add(slaExecution.outcome());
        traceIds.add(slaExecution.traceId());

        Set<String> evidenceIds = new LinkedHashSet<>(draft.evidenceIds());
        if (slaExecution.result() != null) {
            slaExecution.result().toEvidence(caseId).forEach(e -> {
                persistEvidence(e, investigationId);
                evidenceIds.add(e.evidenceId());
            });
        }
        Set<String> mergedTraceIds = new LinkedHashSet<>(draft.toolTraceIds());
        mergedTraceIds.addAll(traceIds);
        return new FraudCaseRecommendation(
                caseId, draft.classification(), draft.riskLevel(),
                draft.recommendedRoute(), draft.confidence(),
                draft.humanReviewRequired(), List.copyOf(evidenceIds),
                draft.policyCitations(), draft.missingInformation(),
                draft.ruleIds(), List.copyOf(mergedTraceIds), draft.rationale());
    }

    /** Fixed manual-review stub — emits only when even the deterministic
     *  fallback cannot produce a valid recommendation. */
    private FraudCaseRecommendation lastResort(String caseId, List<String> traceIds) {
        return new FraudCaseRecommendation(
                caseId, CaseClassification.INSUFFICIENT_EVIDENCE,
                RiskLevel.MEDIUM, RecommendedRoute.MANUAL_REVIEW, 0.10, true,
                List.of(), List.of(),
                List.of("processor output unavailable or invalid"),
                List.of(LAST_RESORT_RULE_ID), List.copyOf(traceIds),
                "Deterministic last-resort: the configured processor could not "
                        + "produce a valid recommendation, so the case is routed "
                        + "to manual review with no further action taken.");
    }

    private Set<String> evidenceIdsOf(List<EvidenceItem> evidence) {
        return evidence.stream().map(EvidenceItem::evidenceId)
                .collect(Collectors.toSet());
    }

    /** All trace ids persisted for the case — includes model-driven calls. */
    private Set<String> caseTraceIds(String caseId) {
        return traceRepository.findByCaseIdOrderByExecutedAt(caseId).stream()
                .map(ToolTraceEntity::getTraceId).collect(Collectors.toSet());
    }

    private Set<String> knownRuleIdsFor(RecommendationMode mode) {
        return switch (mode) {
            case RULES -> RulesFraudCaseAgent.RULE_IDS;
            case RULES_FALLBACK -> {
                Set<String> ids = new LinkedHashSet<>(RulesFraudCaseAgent.RULE_IDS);
                ids.add(LAST_RESORT_RULE_ID);
                yield Set.copyOf(ids);
            }
            case SPRING_AI -> Set.of();
        };
    }

    private static String bounded(String text) {
        return text.length() <= AUDIT_REASON_MAX
                ? text : text.substring(0, AUDIT_REASON_MAX);
    }

    @Transactional(readOnly = true)
    public List<EvidenceItem> evidenceFor(String caseId) {
        return evidenceRepository.findByCaseIdOrderByObservedAt(caseId).stream()
                .map(e -> new EvidenceItem(e.getEvidenceId(), e.getCaseId(),
                        e.getEvidenceType(), e.getSeverity(), e.getValueSummary(),
                        e.getProvenance(), e.getObservedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EvidenceItemEntity> evidenceEntitiesFor(String caseId) {
        return evidenceRepository.findByCaseIdOrderByObservedAt(caseId);
    }

    @Transactional(readOnly = true)
    public List<ToolTraceEntity> tracesFor(String caseId) {
        return traceRepository.findByCaseIdOrderByExecutedAt(caseId);
    }

    @Transactional(readOnly = true)
    public RecommendationEntity latestRecommendation(String caseId) {
        return recommendationRepository.findTopByCaseIdOrderByCreatedAtDesc(caseId)
                .orElseThrow(() -> new RecommendationNotFoundException(caseId));
    }

    private void collect(ToolExecutionService.Execution execution, String caseId,
                         List<ToolOutcome> outcomes, List<String> traceIds,
                         List<EvidenceItem> runEvidence) {
        outcomes.add(execution.outcome());
        traceIds.add(execution.traceId());
        ToolResult result = execution.result();
        if (result != null) {
            runEvidence.addAll(result.toEvidence(caseId));
        }
    }

    private void persistEvidence(EvidenceItem item, String investigationId) {
        // Content-derived ids make re-runs idempotent.
        evidenceRecorder.record(item, investigationId);
    }

    private EvidenceItem claimEvidence(FraudCase fraudCase) {
        ClaimFacts claim = fraudCase.claim();
        String summary = "issueType=" + claim.issueType()
                + "; merchantRecognised=" + claim.merchantRecognised()
                + "; cardPossession=" + claim.cardPossession();
        return new EvidenceItem(
                Determinism.shortId("EVI", 16, fraudCase.caseId(), "claim", summary),
                fraudCase.caseId(), EvidenceType.CUSTOMER_CLAIM, EvidenceSeverity.INFO,
                summary, EvidenceProvenance.CUSTOMER_INTAKE, fraudCase.reportedAt());
    }
}
