package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end spring-ai mode on the real context: H2 + Flyway + JPA +
 * InvestigationService + SpringAiFraudCaseAgent, with {@link StubChatModel}
 * as the only "provider". No credentials, no network.
 *
 * <p>Verifies: valid output persists as SPRING_AI; every failure class
 * falls back to the deterministic rules processor persisted as
 * RULES_FALLBACK, with the full audit trail; invalid output never persists.
 */
@SpringBootTest(properties = "capstone.processor-mode=spring-ai")
@AutoConfigureMockMvc
class SpringAiModeInvestigationTest {

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubChatModel stub;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private EvidenceItemRepository evidenceRepository;

    @Autowired
    private AuditEventAppender auditAppender;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    private String createCase() {
        return fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
    }

    /** Standard fixture with an overridden transaction ref / statement. */
    private String createCase(String transactionRef, String maskedCustomerRef,
                              String maskedMerchant, String statement) {
        var base = TestFixtures.validRequest();
        return fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(),
                new CustomerDetails(maskedCustomerRef, base.customer().homeRegion()),
                new TransactionDetails(transactionRef, base.transaction().amount(),
                        base.transaction().currency(), base.transaction().occurredAt(),
                        base.transaction().status(), base.transaction().channel(),
                        maskedMerchant),
                new ClaimDetails(base.claim().issueType(), statement,
                        base.claim().cardPossession(), base.claim().merchantRecognised()),
                base.consent())).caseId();
    }

    private List<AuditEventType> auditTypes(String caseId) {
        return auditAppender.findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
    }

    /** Deferred script: build valid output citing this case's real evidence. */
    private void enqueueValidFor(String caseId) {
        stub.enqueueFunction(prompt -> {
            List<String> evidenceIds = evidenceRepository
                    .findByCaseIdOrderByObservedAt(caseId).stream()
                    .map(e -> e.getEvidenceId()).toList();
            String ids = evidenceIds.stream()
                    .map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
            String json = """
                    {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                     "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                     "confidence":0.8,"humanReviewRequired":true,
                     "evidenceIds":[%s],"missingInformation":["check auth logs"],
                     "toolTraceIds":[],"policyCitations":[],
                     "rationale":"Device and location anomalies corroborate the denial."}
                    """.formatted(caseId, ids);
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content(json).build())));
        });
    }

    private void enqueueRaw(String json) {
        stub.enqueueJson(json);
    }

    @Test
    void validModelOutputPersistsAsSpringAiMode() throws Exception {
        String caseId = createCase();
        enqueueValidFor(caseId);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SPRING_AI"))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.classification").value("UNAUTHORISED_TRANSACTION"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        assertThat(rec.getMode()).isEqualTo(RecommendationMode.SPRING_AI);
        assertThat(rec.getRuleIds()).isEmpty();
        assertThat(rec.isHumanReviewRequired()).isTrue();
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_STARTED,
                AuditEventType.MODEL_INVOCATION_COMPLETED,
                AuditEventType.RECOMMENDATION_CREATED);
        assertThat(auditTypes(caseId)).doesNotContain(
                AuditEventType.RULES_FALLBACK_STARTED);
    }

    @Test
    void modelToolCallExecutesAndTracesAreReal() {
        String caseId = createCase();
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"TXN-2026-784102\"}");
        enqueueValidFor(caseId);

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.SPRING_AI);
        // Five fixed tools + one model-driven call + SLA = 7 traces.
        assertThat(investigationService.tracesFor(caseId)).hasSizeGreaterThanOrEqualTo(7);
        assertThat(investigationService.tracesFor(caseId).stream()
                .filter(t -> t.getToolName().equals("getTransactionDetails"))
                .count()).isEqualTo(2); // orchestrator run + model-driven run
    }

    @Test
    void providerFailureFallsBackToRulesAndAudits() {
        String caseId = createCase();
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);

        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(result.fraudCase().state()).isEqualTo(CaseState.READY_FOR_REVIEW);
        assertThat(result.recommendation().classification())
                .isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
        assertThat(result.recommendation().humanReviewRequired()).isTrue();
        assertThat(result.recommendation().ruleIds()).isNotEmpty();

        RecommendationEntity persisted = investigationService.latestRecommendation(caseId);
        assertThat(persisted.getMode()).isEqualTo(RecommendationMode.RULES_FALLBACK);

        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_STARTED,
                AuditEventType.MODEL_INVOCATION_FAILED,
                AuditEventType.RULES_FALLBACK_STARTED,
                AuditEventType.RULES_FALLBACK_COMPLETED,
                AuditEventType.RECOMMENDATION_CREATED);
    }

    @Test
    void malformedOutputFallsBackAndIsNeverPersisted() {
        String caseId = createCase();
        enqueueRaw("not json {{{");

        investigationService.investigate(caseId);
        RecommendationEntity persisted = investigationService.latestRecommendation(caseId);
        assertThat(persisted.getMode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_OUTPUT_REJECTED,
                AuditEventType.RULES_FALLBACK_STARTED,
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }

    @Test
    void humanReviewFalseOutputIsRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"FC-IGNORED","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.99,"humanReviewRequired":false,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"skips review"}
                """);
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
        assertThat(investigationService.latestRecommendation(caseId)
                .isHumanReviewRequired()).isTrue();
    }

    @Test
    void foreignEvidenceIdsAreRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":["EVI-FOREIGN-CASE-99"],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"cites evidence from another case"}
                """.formatted(caseId));
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void fabricatedPolicyCitationsAreRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":["POL-FAKE-1"],"rationale":"cites policy"}
                """.formatted(caseId));
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
        // No citation rows ever persisted.
        assertThat(investigationService.latestRecommendation(caseId)
                .getRecommendationId()).isNotBlank();
    }

    @Test
    void authenticationOutcomeIsNeverInvented() {
        String caseId = createCase();
        enqueueValidFor(caseId);
        investigationService.investigate(caseId);
        // The pinned fixture reports authOutcome=UNAVAILABLE — the missing
        // item must remain missing, not resolved as success or failure.
        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        assertThat(rec.getMissingInformation()).isNotEmpty();
        assertThat(rec.getRationale()).doesNotContainIgnoringCase("authentication succeeded")
                .doesNotContainIgnoringCase("authOutcome=SUCCESS");
    }

    @Test
    void ruleIdsClaimedByModelAreRejected() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"ruleIds":["R003"],
                 "rationale":"claims deterministic rules"}
                """.formatted(caseId));
        // ruleIds is not part of the output DTO — the model cannot claim
        // deterministic rules. Either the unknown property is rejected
        // (→ fallback) or dropped (→ persisted SPRING_AI with no rule ids).
        investigationService.investigate(caseId);
        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        if (rec.getMode() == RecommendationMode.SPRING_AI) {
            assertThat(rec.getRuleIds()).isEmpty();
        } else {
            assertThat(rec.getMode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        }
        assertThat(rec.isHumanReviewRequired()).isTrue();
    }

    // ---------- F02-F04: provider failure classes all take the same safe path ----------

    @Test
    void providerTimeoutFallsBackToRules() {
        String caseId = createCase();
        stub.enqueueFailure(new ResourceAccessException(
                "I/O error", new SocketTimeoutException("read timed out")));
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(result.recommendation().humanReviewRequired()).isTrue();
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_FAILED,
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }

    @Test
    void providerAuthFailureFallsBackToRules() {
        String caseId = createCase();
        stub.enqueueFailure(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8));
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_FAILED,
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }

    @Test
    void providerRateLimitFallsBackToRules() {
        String caseId = createCase();
        stub.enqueueFailure(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8));
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_FAILED,
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }

    // ---------- F07/F09/F12/F14: contract violations are rejected, never persisted ----------

    @Test
    void missingMandatoryFieldIsRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,"evidenceIds":[],
                 "missingInformation":[],"toolTraceIds":[],"policyCitations":[],
                 "rationale":"classification field absent"}
                """.formatted(caseId));
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void wrongCaseIdIsRejectedThenFallsBack() {
        String caseB = createCase("TXN-2026-999999", "CUST-••••-9999",
                "OTHER MERCHANT ••••55",
                "I do not recognise this purchase and still have my card.");
        String caseA = createCase();
        // The model returns a recommendation naming case B.
        stub.enqueueJson("""
                {"caseId":"%s","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"wrong case"}
                """.formatted(caseB));
        investigationService.investigate(caseA);
        RecommendationEntity persisted = investigationService.latestRecommendation(caseA);
        assertThat(persisted.getMode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(persisted.getCaseId()).isEqualTo(caseA);
        assertThat(auditTypes(caseA))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void foreignToolTraceIdsAreRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":["TRC-FOREIGN-99"],"policyCitations":[],
                 "rationale":"cites a trace from another case"}
                """.formatted(caseId));
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void rationaleClaimingExecutedActionIsRejectedThenFallsBack() {
        String caseId = createCase();
        enqueueRaw("""
                {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],
                 "rationale":"I have blocked the card for the customer."}
                """.formatted(caseId));
        investigationService.investigate(caseId);
        assertThat(investigationService.latestRecommendation(caseId).getMode())
                .isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    // ---------- F15/§12: tool bounds are enforced end to end ----------

    @Test
    void toolCallBudgetExceededIsBoundedAndCallsDoNotExecute() {
        String caseId = createCase();
        // Eight model-driven calls against a budget of six.
        String args = "{\"maskedCustomerRef\":\"CUST-••••-4821\"}";
        stub.enqueueToolCalls(
                "getDeviceSignals", args, "getDeviceSignals", args,
                "getDeviceSignals", args, "getDeviceSignals", args,
                "getDeviceSignals", args, "getDeviceSignals", args,
                "getDeviceSignals", args, "getDeviceSignals", args);
        enqueueValidFor(caseId);
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.SPRING_AI);
        // 5 orchestrator tools + 6 budgeted model calls + 1 SLA = 12 traces;
        // the 2 over-budget calls produced LIMIT_EXCEEDED, never traces.
        assertThat(investigationService.tracesFor(caseId)).hasSize(12);
    }

    @Test
    void neverEndingToolCallsHitRoundBoundThenFallBack() {
        String caseId = createCase();
        String args = "{\"maskedCustomerRef\":\"CUST-••••-4821\"}";
        for (int i = 0; i < 5; i++) {
            stub.enqueueToolCalls("getDeviceSignals", args);
        }
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_FAILED,
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }

    @Test
    void crossCaseToolCallIsBlockedAndNoForeignDataLeaks() {
        createCase("TXN-2026-999999", "CUST-••••-9999",
                "OTHER MERCHANT ••••55",
                "I do not recognise this purchase and still have my card.");
        String caseA = createCase();
        // The model tries to pull case B's transaction into case A.
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"TXN-2026-999999\"}");
        enqueueValidFor(caseA);
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseA);
        assertThat(result.mode()).isEqualTo(RecommendationMode.SPRING_AI);
        // The foreign call was rejected before execution: only the
        // orchestrator's own transaction trace exists for case A.
        assertThat(investigationService.tracesFor(caseA).stream()
                .filter(t -> t.getToolName().equals("getTransactionDetails"))
                .count()).isEqualTo(1);
        assertThat(investigationService.evidenceFor(caseA))
                .allSatisfy(e -> assertThat(e.valueSummary())
                        .doesNotContain("TXN-2026-999999"));
    }

    // ---------- §25: when rules also cannot classify, abstain safely ----------

    @Test
    void aiFailureWithUnfindableTransactionYieldsInsufficientEvidence() {
        String caseId = createCase("TXN-4040-404040", "CUST-••••-4821",
                "NORTHSTAR DIGITAL ••••73",
                "I do not recognise this purchase and still have my card.");
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));
        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(result.recommendation().classification())
                .isEqualTo(CaseClassification.INSUFFICIENT_EVIDENCE);
        assertThat(result.recommendation().recommendedRoute())
                .isEqualTo(RecommendedRoute.MANUAL_REVIEW);
        assertThat(result.recommendation().humanReviewRequired()).isTrue();
        assertThat(result.recommendation().missingInformation()).isNotEmpty();
        assertThat(result.fraudCase().state()).isEqualTo(CaseState.INCOMPLETE);
        assertThat(auditTypes(caseId)).contains(
                AuditEventType.RULES_FALLBACK_COMPLETED);
    }
}
