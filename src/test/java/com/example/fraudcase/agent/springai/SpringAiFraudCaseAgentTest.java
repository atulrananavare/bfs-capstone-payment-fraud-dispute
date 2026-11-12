package com.example.fraudcase.agent.springai;

import com.example.fraudcase.DomainFixtures;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.application.EvidenceRecorder;
import com.example.fraudcase.application.ToolExecutionService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.ToolTraceEntity;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.TransactionDetailsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpringAiFraudCaseAgent against a fully scripted {@link StubChatModel} —
 * no live provider. Covers valid output, every malformed-output path, the
 * bounded tool-call loop and the provider-failure contract.
 */
class SpringAiFraudCaseAgentTest {

    private static final String PROMPT = "classpath:prompts/fraud-case-system-prompt.st";

    private StubChatModel stub;
    private SpringAiFraudCaseAgent agent;
    private AuditService auditService;
    private ToolTraceRepository traceRepository;
    private FraudCaseContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CapstoneProperties properties = new CapstoneProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        auditService = mock(AuditService.class);
        traceRepository = mock(ToolTraceRepository.class);
        ToolExecutionService toolExecution =
                new ToolExecutionService(traceRepository, auditService, clock);
        EvidenceRecorder evidenceRecorder =
                new EvidenceRecorder(mock(EvidenceItemRepository.class));
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                new TransactionDetailsTool(properties, clock),
                new DeviceSignalsTool(properties, clock),
                new CompareRecentLocationsTool(properties, clock),
                new MerchantRiskProfileTool(properties, clock),
                new PriorDisputesTool(properties, clock),
                new CaseSlaTool(properties, clock),
                toolExecution, evidenceRecorder,
                properties.getTools().getTimeoutMs(), properties.getAi().getMaxToolCalls());

        stub = new StubChatModel();
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stub);

        agent = new SpringAiFraudCaseAgent(provider, ToolCallingManager.builder().build(),
                adapter, auditService, properties, new ClassPathResource(
                "prompts/fraud-case-system-prompt.st"));

        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        context = new FraudCaseContext(
                fraudCase,
                List.of(DomainFixtures.evidence(fraudCase.caseId(),
                        com.example.fraudcase.domain.EvidenceProvenance.TRANSACTION_TOOL,
                        com.example.fraudcase.domain.EvidenceSeverity.HIGH, "tx found")),
                DomainFixtures.sampleCaseOutcomes("UNAVAILABLE", "false", "UNTRUSTED", "HIGH"),
                List.of("TRC-1"), "INV-TEST01");
    }

    private static String validJson(String caseId) {
        return """
                {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":["EVI-TRANSACTION_TOOL-1"],"missingInformation":[],
                 "toolTraceIds":["TRC-1"],"policyCitations":[],"ruleIds":[],
                 "rationale":"Two independent anomalies support unauthorised use."}
                """.formatted(caseId);
    }

    @Test
    void validStructuredOutputMapsToDomainRecommendation() {
        stub.enqueueJson(validJson(context.caseId()));
        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec.caseId()).isEqualTo(context.caseId());
        assertThat(rec.classification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
        assertThat(rec.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.FRAUD_INVESTIGATION);
        assertThat(rec.confidence()).isEqualTo(0.8);
        assertThat(rec.humanReviewRequired()).isTrue();
        assertThat(rec.ruleIds()).isEmpty();
        assertThat(rec.policyCitations()).isEmpty();
        verify(auditService).record(eq(context.caseId()),
                eq(AuditEventType.MODEL_INVOCATION_STARTED), any(), any());
        verify(auditService).record(eq(context.caseId()),
                eq(AuditEventType.MODEL_INVOCATION_COMPLETED), any(), any());
    }

    @Test
    void providerExceptionBecomesProcessingException() {
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(SpringAiProcessingException.class);
    }

    @Test
    void missingChatModelFailsWithoutInvocation() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> empty = mock(ObjectProvider.class);
        SpringAiFraudCaseAgent noModel = new SpringAiFraudCaseAgent(
                empty, ToolCallingManager.builder().build(), mock(SpringAiToolAdapter.class),
                auditService, new CapstoneProperties(), new ClassPathResource(
                "prompts/fraud-case-system-prompt.st"));
        assertThatThrownBy(() -> noModel.analyze(context))
                .isInstanceOf(SpringAiProcessingException.class)
                .hasMessageContaining("no ChatModel");
    }

    @Test
    void malformedJsonIsRejected() {
        stub.enqueueJson("this is not json at all");
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void emptyModelOutputIsRejected() {
        stub.enqueueJson("");
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void unknownEnumValueIsRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"APPROVE_REFUND",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"bad enum"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("classification");
    }

    @Test
    void unknownRouteAndRiskValuesAreRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"CRITICAL","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"bad risk enum"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("riskLevel");

        stub.reset();
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"BLOCK_THE_CARD",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"bad route enum"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("recommendedRoute");
    }

    @Test
    void missingMandatoryFieldIsRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","riskLevel":"LOW",
                 "recommendedRoute":"MANUAL_REVIEW","confidence":0.9,
                 "humanReviewRequired":true,"evidenceIds":[],
                 "missingInformation":[],"toolTraceIds":[],"policyCitations":[],
                 "rationale":"no classification supplied"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void wrongCaseIdIsRejected() {
        stub.enqueueJson(validJson("FC-OTHER999"));
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("caseId");
    }

    @Test
    void missingCaseIdIsBoundToTheInvestigatedCase() {
        // A missing caseId cannot reach another case — the domain value is
        // always bound from the trusted context.
        stub.enqueueJson("""
                {"classification":"GENUINE_TRANSACTION","riskLevel":"LOW",
                 "recommendedRoute":"MANUAL_REVIEW","confidence":0.9,
                 "humanReviewRequired":true,"evidenceIds":["EVI-TRANSACTION_TOOL-1"],
                 "missingInformation":[],"toolTraceIds":["TRC-1"],
                 "policyCitations":[],"rationale":"benign signals only"}
                """);
        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec.caseId()).isEqualTo(context.caseId());
    }

    @Test
    void rationaleContainingSystemPromptMaterialIsRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],
                 "rationale":"You are a payment-fraud INVESTIGATION AND RECOMMENDATION ASSISTANT. Non-negotiable rules follow."}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("system-prompt");
    }

    @Test
    void humanReviewRequiredFalseIsRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":false,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"claims no review needed"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void outOfRangeConfidenceIsRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":1.5,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":[],"rationale":"overconfident"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void fabricatedPolicyCitationsAreRejected() {
        stub.enqueueJson("""
                {"caseId":"FC-TEST0001","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],"toolTraceIds":[],
                 "policyCitations":["POL-123 v1 §4.2"],"rationale":"cites fake policy"}
                """);
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("policy");
    }

    @Test
    void modelToolCallExecutesRealToolThenReturnsFinalAnswer() {
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}");
        stub.enqueueJson(validJson(context.caseId()));

        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec.classification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);

        // The tool genuinely ran: a real trace row was persisted.
        ArgumentCaptor<ToolTraceEntity> traces = ArgumentCaptor.forClass(ToolTraceEntity.class);
        verify(traceRepository, atLeastOnce()).save(traces.capture());
        assertThat(traces.getAllValues())
                .anyMatch(t -> t.getToolName().equals("getTransactionDetails"));

        // Two model invocations: tool-call round + final answer.
        assertThat(stub.prompts()).hasSize(2);
    }

    @Test
    void foreignToolCallDoesNotExecuteButLoopContinues() {
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"TXN-9999-FOREIGN\"}");
        stub.enqueueJson(validJson(context.caseId()));
        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec).isNotNull();
        // Rejected before execution → no tool ever ran, no trace persisted.
        verify(traceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void neverEndingToolCallsHitTheRoundBound() {
        for (int i = 0; i < 10; i++) {
            stub.enqueueToolCalls("getTransactionDetails",
                    "{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}");
        }
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(SpringAiProcessingException.class)
                .hasMessageContaining("round bound");
    }
}
