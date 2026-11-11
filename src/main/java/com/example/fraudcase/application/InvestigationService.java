package com.example.fraudcase.application;

import com.example.fraudcase.agent.common.RecommendationValidator;
import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
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
import com.example.fraudcase.domain.RecommendationMode;
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
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.Determinism;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.ToolResult;
import com.example.fraudcase.tools.TransactionDetailsTool;
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

    private final FraudCaseStore caseStore;
    private final EvidenceItemRepository evidenceRepository;
    private final ToolTraceRepository traceRepository;
    private final RecommendationRepository recommendationRepository;
    private final PolicyCitationRepository citationRepository;
    private final ToolExecutionService toolExecution;
    private final AuditService auditService;
    private final RulesFraudCaseAgent rulesAgent;
    private final RecommendationValidator recommendationValidator;
    private final CapstoneProperties properties;
    private final Clock clock;

    private final TransactionDetailsTool transactionTool;
    private final DeviceSignalsTool deviceTool;
    private final CompareRecentLocationsTool locationTool;
    private final MerchantRiskProfileTool merchantTool;
    private final PriorDisputesTool priorDisputesTool;
    private final CaseSlaTool slaTool;

    public InvestigationService(
            FraudCaseStore caseStore,
            EvidenceItemRepository evidenceRepository,
            ToolTraceRepository traceRepository,
            RecommendationRepository recommendationRepository,
            PolicyCitationRepository citationRepository,
            ToolExecutionService toolExecution,
            AuditService auditService,
            RulesFraudCaseAgent rulesAgent,
            RecommendationValidator recommendationValidator,
            CapstoneProperties properties,
            Clock clock,
            TransactionDetailsTool transactionTool,
            DeviceSignalsTool deviceTool,
            CompareRecentLocationsTool locationTool,
            MerchantRiskProfileTool merchantTool,
            PriorDisputesTool priorDisputesTool,
            CaseSlaTool slaTool) {
        this.caseStore = caseStore;
        this.evidenceRepository = evidenceRepository;
        this.traceRepository = traceRepository;
        this.recommendationRepository = recommendationRepository;
        this.citationRepository = citationRepository;
        this.toolExecution = toolExecution;
        this.auditService = auditService;
        this.rulesAgent = rulesAgent;
        this.recommendationValidator = recommendationValidator;
        this.properties = properties;
        this.clock = clock;
        this.transactionTool = transactionTool;
        this.deviceTool = deviceTool;
        this.locationTool = locationTool;
        this.merchantTool = merchantTool;
        this.priorDisputesTool = priorDisputesTool;
        this.slaTool = slaTool;
    }

    public record InvestigationResult(
            String investigationId,
            FraudCase fraudCase,
            FraudCaseRecommendation recommendation,
            int toolsExecuted,
            int evidenceCount,
            List<ToolOutcome> toolOutcomes) {
    }

    @Transactional(noRollbackFor = RecommendationValidationException.class)
    public InvestigationResult investigate(String caseId) {
        if (properties.getProcessorMode() != CapstoneProperties.ProcessorMode.RULES) {
            throw new ProcessorModeUnavailableException(properties.getProcessorMode().name());
        }

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

        // ---- ASSESS + RECOMMEND (rules mode) ----
        List<EvidenceItem> allEvidence = evidenceFor(caseId);
        FraudCaseContext context = new FraudCaseContext(
                fraudCase, allEvidence, outcomes, traceIds);
        FraudCaseRecommendation draft = rulesAgent.analyze(context);

        // SLA target for the proposed route — evidence + trace included in
        // the final recommendation.
        ToolExecutionService.Execution slaExecution = toolExecution.run(slaTool,
                new CaseSlaTool.Request(
                        draft.recommendedRoute(), draft.riskLevel(), fraudCase.reportedAt()),
                caseId, investigationId);
        outcomes.add(slaExecution.outcome());
        traceIds.add(slaExecution.traceId());
        if (slaExecution.result() != null) {
            slaExecution.result().toEvidence(caseId)
                    .forEach(e -> persistEvidence(e, investigationId));
        }

        allEvidence = evidenceFor(caseId);
        Set<String> evidenceIds = new LinkedHashSet<>(draft.evidenceIds());
        if (slaExecution.result() != null) {
            slaExecution.result().toEvidence(caseId)
                    .forEach(e -> evidenceIds.add(e.evidenceId()));
        }
        FraudCaseRecommendation recommendation = new FraudCaseRecommendation(
                fraudCase.caseId(),
                draft.classification(),
                draft.riskLevel(),
                draft.recommendedRoute(),
                draft.confidence(),
                draft.humanReviewRequired(),
                List.copyOf(evidenceIds),
                draft.policyCitations(),
                draft.missingInformation(),
                draft.ruleIds(),
                List.copyOf(traceIds),
                draft.rationale());

        List<String> violations = recommendationValidator.validate(
                recommendation,
                caseId,
                allEvidence.stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet()),
                new LinkedHashSet<>(traceIds),
                RecommendationMode.RULES,
                RulesFraudCaseAgent.RULE_IDS);

        if (!violations.isEmpty()) {
            auditService.record(caseId, AuditEventType.RECOMMENDATION_REJECTED,
                    ActorType.SYSTEM, String.join("; ", violations));
            fraudCase.transitionTo(CaseState.INCOMPLETE);
            caseStore.save(fraudCase);
            auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                    ActorType.SYSTEM, "INCOMPLETE");
            throw new RecommendationValidationException(violations);
        }

        String recommendationId = "REC-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        RecommendationEntity entity = new RecommendationEntity(
                recommendationId, caseId, investigationId,
                RecommendationMode.RULES,
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
                        c.chunkId(), c.score())));
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
                outcomes.size(), allEvidence.size(), List.copyOf(outcomes));
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
        if (evidenceRepository.existsById(item.evidenceId())) {
            return;
        }
        evidenceRepository.save(new EvidenceItemEntity(
                item.evidenceId(), item.caseId(), investigationId,
                item.type(), item.severity(), item.valueSummary(),
                item.provenance(), item.observedAt()));
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
