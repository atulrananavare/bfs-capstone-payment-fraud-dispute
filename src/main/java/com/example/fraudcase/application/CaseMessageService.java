package com.example.fraudcase.application;

import com.example.fraudcase.agent.springai.InvalidModelOutputException;
import com.example.fraudcase.agent.springai.SpringAiFollowUpAgent;
import com.example.fraudcase.api.CaseMessageRequest;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseMessage;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.ConversationTurn;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.FollowUpAnswer;
import com.example.fraudcase.domain.FollowUpContext;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.PolicyPassage;
import com.example.fraudcase.memory.CaseConversationStore;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.FraudCaseStore;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.persistence.RecommendationRepository;
import com.example.fraudcase.rag.PolicyQueryBuilder;
import com.example.fraudcase.rag.PolicyRetrievalOutcome;
import com.example.fraudcase.rag.PolicyRetriever;
import com.example.fraudcase.observability.CaseMetrics;
import com.example.fraudcase.observability.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Case-scoped conversational follow-up (Phase 5).
 *
 * <p>Flow for {@code POST /api/cases/{caseId}/messages}:
 * <pre>
 *   resolve case (404) -> spring-ai mode gate (409 in rules mode)
 *     -> idempotent-replay check on client_reference
 *     -> bounded memory window via Spring AI ChatMemory (conversationId=caseId)
 *     -> persist USER turn (case-local sequence, audited)
 *     -> build context: case + persisted evidence + latest validated
 *        recommendation/citations + fresh Phase 4 retrieval + window
 *     -> SpringAiFollowUpAgent.answer (allow-listed tools only)
 *     -> persist approved ASSISTANT turn -> audited response
 * </pre>
 *
 * <p>Every read is case-scoped at the data layer; the model cannot change
 * conversation scope, case state or review obligations. Malformed model
 * output is never persisted — a deterministic safe answer is stored
 * instead.
 */
@Service
public class CaseMessageService {

    private static final Logger log =
            LoggerFactory.getLogger(CaseMessageService.class);

    private static final int AUDIT_REASON_MAX = 200;

    private static final FollowUpAnswer SAFE_FALLBACK = new FollowUpAnswer(
            "The follow-up assistant could not produce a validated answer "
                    + "for this case. The question remains for a human "
                    + "reviewer; no action was taken.",
            List.of(), List.of(), List.of("assistant answer unavailable"), true);

    private final FraudCaseStore caseStore;
    private final CaseConversationStore conversationStore;
    private final ChatMemory chatMemory;
    private final EvidenceItemRepository evidenceRepository;
    private final RecommendationRepository recommendationRepository;
    private final PolicyCitationRepository citationRepository;
    private final ObjectProvider<PolicyRetriever> policyRetriever;
    private final SpringAiFollowUpAgent followUpAgent;
    private final AuditService auditService;
    private final CapstoneProperties properties;
    private final CaseMetrics metrics;

    /** Cases whose history has been reloaded once in this JVM. */
    private final Set<String> reconstructed = ConcurrentHashMap.newKeySet();

    public CaseMessageService(FraudCaseStore caseStore,
                              CaseConversationStore conversationStore,
                              ChatMemory chatMemory,
                              EvidenceItemRepository evidenceRepository,
                              RecommendationRepository recommendationRepository,
                              PolicyCitationRepository citationRepository,
                              ObjectProvider<PolicyRetriever> policyRetriever,
                              SpringAiFollowUpAgent followUpAgent,
                              AuditService auditService,
                              CapstoneProperties properties,
                              CaseMetrics metrics) {
        this.caseStore = caseStore;
        this.conversationStore = conversationStore;
        this.chatMemory = chatMemory;
        this.evidenceRepository = evidenceRepository;
        this.recommendationRepository = recommendationRepository;
        this.citationRepository = citationRepository;
        this.policyRetriever = policyRetriever;
        this.followUpAgent = followUpAgent;
        this.auditService = auditService;
        this.properties = properties;
        this.metrics = metrics;
    }

    public record MessageResult(CaseMessage userMessage,
                                CaseMessage assistantMessage,
                                FollowUpAnswer answer,
                                boolean idempotentReplay,
                                String answerSource) {
    }

    /**
     * Not transactional as a whole: the user turn and the assistant turn
     * each commit independently (a received message must stay recorded
     * even when answering fails). Ordering integrity comes from the
     * per-case lock inside {@link CaseConversationStore#append}.
     */
    public MessageResult submit(String caseId, CaseMessageRequest request) {
        MDC.put(CorrelationIdFilter.MDC_CASE_ID, caseId);
        try {
            return doSubmit(caseId, request);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_CASE_ID);
        }
    }

    private MessageResult doSubmit(String caseId, CaseMessageRequest request) {
        FraudCase fraudCase = caseStore.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Conversational answering requires the Spring AI adapter. Rules
        // mode keeps working for every other operation — it simply has no
        // model to answer with, and no fake answer is invented.
        if (properties.getProcessorMode()
                != CapstoneProperties.ProcessorMode.SPRING_AI) {
            auditService.record(caseId, AuditEventType.CASE_MESSAGE_REJECTED,
                    ActorType.ANALYST, "conversation unavailable in rules mode");
            throw new ConversationUnavailableException(
                    properties.getProcessorMode().name().toLowerCase());
        }

        // Idempotent replay: a retry carrying the same client reference
        // returns the stored exchange — never a duplicate turn.
        String clientRef = request.clientReference();
        if (clientRef != null && !clientRef.isBlank()) {
            Optional<CaseMessage> prior =
                    conversationStore.byClientReference(caseId, clientRef);
            if (prior.isPresent()) {
                Optional<CaseMessage> reply = conversationStore.atSequence(
                        caseId, prior.get().sequenceNumber() + 1);
                if (reply.isPresent()
                        && reply.get().role() == ConversationRole.ASSISTANT) {
                    return new MessageResult(prior.get(), reply.get(),
                            rebuildStoredAnswer(reply.get(), fraudCase),
                            true, "STORED");
                }
            }
        }

        // ---- Bounded case-keyed memory (Spring AI ChatMemory) ----
        long totalBefore = conversationStore.count(caseId);
        List<ConversationTurn> window = memoryWindow(caseId);
        if (totalBefore > 0 && reconstructed.add(caseId)) {
            auditService.record(caseId, AuditEventType.CASE_MEMORY_RECONSTRUCTED,
                    ActorType.SYSTEM, "messages=" + totalBefore
                            + "|window=" + window.size());
        }
        if (totalBefore > window.size()) {
            auditService.record(caseId, AuditEventType.CASE_MEMORY_LIMIT_APPLIED,
                    ActorType.SYSTEM, "total=" + totalBefore
                            + "|window=" + window.size());
        }

        // ---- Persist the USER turn (audited, immutable) ----
        CaseMessage userMessage;
        try {
            userMessage = conversationStore.append(
                    caseId, ConversationRole.USER, request.message(),
                    clientRef, null);
        } catch (DataIntegrityViolationException duplicate) {
            // Concurrent retry with the same client reference: the row
            // already exists — replay the stored exchange instead of
            // creating a duplicate turn.
            if (clientRef != null && !clientRef.isBlank()) {
                Optional<CaseMessage> prior =
                        conversationStore.byClientReference(caseId, clientRef);
                if (prior.isPresent()) {
                    Optional<CaseMessage> reply = conversationStore.atSequence(
                            caseId, prior.get().sequenceNumber() + 1);
                    if (reply.isPresent()
                            && reply.get().role() == ConversationRole.ASSISTANT) {
                        return new MessageResult(prior.get(), reply.get(),
                                rebuildStoredAnswer(reply.get(), fraudCase),
                                true, "STORED");
                    }
                }
            }
            throw duplicate;
        }
        auditService.record(caseId, AuditEventType.CASE_MESSAGE_RECEIVED,
                ActorType.ANALYST, userMessage.messageId()
                        + "|seq=" + userMessage.sequenceNumber()
                        + "|hash=" + userMessage.contentHash()
                                .substring(0, 12));

        // ---- Context for the follow-up ----
        String correlationId = "FUP-" + userMessage.messageId();
        List<EvidenceItem> evidence = evidenceFor(caseId);
        FraudCaseRecommendation recommendation = latestRecommendation(caseId);
        List<PolicyPassage> passages = new ArrayList<>();
        PolicyContextStatus policyStatus = retrievePolicy(
                fraudCase, correlationId, passages);

        FollowUpContext context = new FollowUpContext(
                fraudCase, evidence, recommendation, passages, policyStatus,
                window, request.message(), correlationId);

        // ---- Model answer -> validated -> persisted ----
        FollowUpAnswer answer;
        String answerSource = "MODEL";
        try {
            answer = followUpAgent.answer(context);
        } catch (InvalidModelOutputException ex) {
            auditService.record(caseId, AuditEventType.MODEL_OUTPUT_REJECTED,
                    ActorType.SYSTEM, bounded(ex.getMessage()));
            if (ex.getMessage() != null && ex.getMessage().toLowerCase()
                    .contains("policy")) {
                auditService.record(caseId,
                        AuditEventType.POLICY_CITATION_REJECTED,
                        ActorType.SYSTEM, bounded(ex.getMessage()));
            }
            answer = SAFE_FALLBACK;
            answerSource = "SAFE_FALLBACK";
        } catch (RuntimeException ex) {
            auditService.record(caseId, AuditEventType.MODEL_INVOCATION_FAILED,
                    ActorType.SYSTEM, bounded(ex.getClass().getSimpleName()));
            answer = SAFE_FALLBACK;
            answerSource = "SAFE_FALLBACK";
        }

        // Only the approved user-visible answer text is persisted — never
        // raw model output.
        CaseMessage assistantMessage = conversationStore.append(
                caseId, ConversationRole.ASSISTANT, answer.answer(),
                null, referencesSummary(answer));
        auditService.record(caseId, AuditEventType.CASE_MESSAGE_RESPONSE_CREATED,
                ActorType.SYSTEM, assistantMessage.messageId()
                        + "|seq=" + assistantMessage.sequenceNumber()
                        + "|evidence=" + answer.evidenceIds().size()
                        + "|citations=" + answer.policyCitations().size());
        metrics.messageAnswered(answerSource.toLowerCase());
        log.info("CASE_MESSAGE_RESPONSE case={} msg={} seq={} ev={} cit={}",
                caseId, assistantMessage.messageId(),
                assistantMessage.sequenceNumber(), answer.evidenceIds().size(),
                answer.policyCitations().size());
        return new MessageResult(userMessage, assistantMessage, answer,
                false, answerSource);
    }

    /** Full persisted history for GET — safe, case-scoped, ordered. */
    @Transactional(readOnly = true)
    public List<CaseMessage> history(String caseId) {
        caseStore.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        return conversationStore.history(caseId);
    }

    /**
     * The bounded active window through Spring AI's case-keyed
     * {@link ChatMemory}: conversationId == caseId. Only USER/ASSISTANT
     * turns enter the prompt; they are rendered as untrusted data.
     */
    private List<ConversationTurn> memoryWindow(String caseId) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (Message m : chatMemory.get(caseId)) {
            ConversationRole role = switch (m.getMessageType()) {
                case USER -> ConversationRole.USER;
                case ASSISTANT -> ConversationRole.ASSISTANT;
                default -> null;
            };
            if (role != null && m.getText() != null) {
                turns.add(new ConversationTurn(role, m.getText()));
            }
        }
        return List.copyOf(turns);
    }

    /**
     * Reuses the Phase 4 {@code retrievePolicyEvidence} capability —
     * read-only, threshold-bounded, version-filtered. Failures become an
     * explicit UNAVAILABLE status, never an exception.
     */
    private PolicyContextStatus retrievePolicy(FraudCase fraudCase,
                                               String correlationId,
                                               List<PolicyPassage> into) {
        PolicyRetriever retriever = policyRetriever.getIfAvailable();
        if (retriever == null) {
            return PolicyContextStatus.NOT_REQUESTED;
        }
        auditService.record(fraudCase.caseId(),
                AuditEventType.POLICY_RETRIEVAL_STARTED,
                ActorType.SYSTEM, correlationId);
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                PolicyQueryBuilder.fromContext(fraudCase, List.of()));
        switch (outcome.status()) {
            case RETRIEVED -> {
                auditService.record(fraudCase.caseId(),
                        AuditEventType.POLICY_RETRIEVAL_COMPLETED,
                        ActorType.SYSTEM, correlationId
                                + "|passages=" + outcome.passages().size());
                into.addAll(outcome.passages());
                return PolicyContextStatus.RETRIEVED;
            }
            case NO_MATCH -> {
                auditService.record(fraudCase.caseId(),
                        AuditEventType.POLICY_RETRIEVAL_NO_MATCH,
                        ActorType.SYSTEM, correlationId);
                return PolicyContextStatus.NO_MATCH;
            }
            default -> {
                auditService.record(fraudCase.caseId(),
                        AuditEventType.POLICY_RETRIEVAL_FAILED,
                        ActorType.SYSTEM, correlationId);
                return PolicyContextStatus.UNAVAILABLE;
            }
        }
    }

    private List<EvidenceItem> evidenceFor(String caseId) {
        return evidenceRepository.findByCaseIdOrderByObservedAt(caseId).stream()
                .map(e -> new EvidenceItem(e.getEvidenceId(), e.getCaseId(),
                        e.getEvidenceType(), e.getSeverity(), e.getValueSummary(),
                        e.getProvenance(), e.getObservedAt()))
                .toList();
    }

    private FraudCaseRecommendation latestRecommendation(String caseId) {
        return recommendationRepository
                .findTopByCaseIdOrderByCreatedAtDesc(caseId)
                .map(e -> toDomainRecommendation(caseId, e))
                .orElse(null);
    }

    private FraudCaseRecommendation toDomainRecommendation(
            String caseId, RecommendationEntity e) {
        List<PolicyCitation> citations = citationRepository
                .findByRecommendationId(e.getRecommendationId()).stream()
                .map(c -> new PolicyCitation(c.getDocumentId(), c.getVersion(),
                        c.getSection(), c.getChunkId(), c.getScore(),
                        c.getTitle(),
                        c.getEffectiveDate() == null
                                ? null : c.getEffectiveDate().toString(),
                        c.getPassage()))
                .toList();
        return new FraudCaseRecommendation(
                caseId, e.getClassification(), e.getRisk(), e.getRoute(),
                e.getConfidence(), e.isHumanReviewRequired(),
                List.copyOf(e.getEvidenceIds()), citations,
                List.copyOf(e.getMissingInformation()),
                List.copyOf(e.getRuleIds()), List.copyOf(e.getToolTraceIds()),
                e.getRationale());
    }

    /** Rebuild an idempotent replay answer from the persisted assistant
     *  turn — the same trusted sources, no model call. */
    private FollowUpAnswer rebuildStoredAnswer(CaseMessage stored,
                                               FraudCase fraudCase) {
        List<String> evidenceIds = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        parseReferences(stored.referencesSummary(), evidenceIds, chunkIds);
        List<PolicyPassage> passages = new ArrayList<>();
        retrievePolicy(fraudCase, "FUP-REPLAY-" + stored.messageId(), passages);
        FraudCaseRecommendation rec = latestRecommendation(fraudCase.caseId());
        java.util.Map<String, PolicyCitation> trusted = new java.util.LinkedHashMap<>();
        for (PolicyPassage p : passages) {
            trusted.put(p.chunkId(), PolicyCitation.from(p));
        }
        if (rec != null) {
            for (PolicyCitation c : rec.policyCitations()) {
                trusted.putIfAbsent(c.chunkId(), c);
            }
        }
        List<PolicyCitation> citations = chunkIds.stream()
                .map(trusted::get).filter(java.util.Objects::nonNull).toList();
        return new FollowUpAnswer(stored.content(), evidenceIds, citations,
                List.of(), true);
    }

    private static String referencesSummary(FollowUpAnswer answer) {
        return "EV:" + String.join(",", answer.evidenceIds())
                + ";CIT:" + answer.policyCitations().stream()
                        .map(PolicyCitation::chunkId)
                        .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static void parseReferences(String refs, List<String> evidenceIds,
                                        List<String> chunkIds) {
        if (refs == null || refs.isBlank()) {
            return;
        }
        for (String part : refs.split(";")) {
            if (part.startsWith("EV:")) {
                for (String id : part.substring(3).split(",")) {
                    if (!id.isBlank()) {
                        evidenceIds.add(id);
                    }
                }
            } else if (part.startsWith("CIT:")) {
                for (String id : part.substring(4).split(",")) {
                    if (!id.isBlank()) {
                        chunkIds.add(id);
                    }
                }
            }
        }
    }

    private static String bounded(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= AUDIT_REASON_MAX
                ? text : text.substring(0, AUDIT_REASON_MAX);
    }
}
