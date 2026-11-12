package com.example.fraudcase.agent.langchain4j;

import com.example.fraudcase.DomainFixtures;
import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.common.CaseToolInvoker;
import com.example.fraudcase.agent.common.InvalidModelOutputException;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.application.EvidenceRecorder;
import com.example.fraudcase.application.ToolExecutionService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.TransactionDetailsTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LangChain4jFraudCaseAgent} against a fully scripted
 * {@link StubLc4jChatModel} — no live provider. Mirrors the Spring AI
 * adapter's contract tests: valid output, malformed-output rejection, the
 * bounded tool-call loop and the provider-failure contract must behave
 * identically on both frameworks.
 */
class LangChain4jFraudCaseAgentTest {

    private static final String PROMPT = "classpath:prompts/fraud-case-system-prompt.st";

    private StubLc4jChatModel stub;
    private LangChain4jFraudCaseAgent agent;
    private FraudCaseContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CapstoneProperties properties = new CapstoneProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        AuditService auditService = mock(AuditService.class);
        ToolTraceRepository traceRepository = mock(ToolTraceRepository.class);
        ToolExecutionService toolExecution =
                new ToolExecutionService(traceRepository, auditService, clock,
                        TestFixtures.testMetrics());
        EvidenceRecorder evidenceRecorder =
                new EvidenceRecorder(mock(EvidenceItemRepository.class));
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                new CaseToolInvoker(
                        new TransactionDetailsTool(properties, clock),
                        new DeviceSignalsTool(properties, clock),
                        new CompareRecentLocationsTool(properties, clock),
                        new MerchantRiskProfileTool(properties, clock),
                        new PriorDisputesTool(properties, clock),
                        new CaseSlaTool(properties, clock),
                        toolExecution, evidenceRecorder,
                        properties.getTools().getTimeoutMs()),
                properties);

        stub = new StubLc4jChatModel();
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stub);

        agent = new LangChain4jFraudCaseAgent(provider, adapter, auditService,
                properties, new ClassPathResource(
                "prompts/fraud-case-system-prompt.st"), TestFixtures.testMetrics());

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
    }

    @Test
    void fencedJsonOutputIsAccepted() {
        stub.enqueueJson("```json\n" + validJson(context.caseId()) + "\n```");
        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec.classification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
    }

    @Test
    void providerExceptionBecomesProcessingException() {
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(LangChain4jProcessingException.class);
    }

    @Test
    void missingModelBecomesProcessingException() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        CapstoneProperties properties = new CapstoneProperties();
        LangChain4jFraudCaseAgent noModel = new LangChain4jFraudCaseAgent(
                empty, mock(LangChain4jToolAdapter.class),
                mock(AuditService.class), properties,
                new ClassPathResource("prompts/fraud-case-system-prompt.st"),
                TestFixtures.testMetrics());
        assertThatThrownBy(() -> noModel.analyze(context))
                .isInstanceOf(LangChain4jProcessingException.class)
                .hasMessageContaining("langchain4j");
    }

    @Test
    void malformedJsonIsRejectedNotMapped() {
        stub.enqueueJson("not json at all");
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void emptyOutputIsRejected() {
        stub.enqueueResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("")).build());
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void foreignCaseIdIsRejected() {
        stub.enqueueJson(validJson("FC-FOREIGN01"));
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("caseId");
    }

    @Test
    void toolCallLoopExecutesThenReadsFinalOutput() {
        // Round 1: model requests the transaction tool; round 2: final JSON.
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}");
        stub.enqueueJson(validJson(context.caseId()));

        FraudCaseRecommendation rec = agent.analyze(context);
        assertThat(rec.classification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
        assertThat(stub.requests()).hasSize(2);
        // Second request must carry the tool-result message for the model.
        assertThat(stub.requests().get(1).messages())
                .anyMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
    }

    @Test
    void toolRoundBoundIsEnforced() {
        for (int i = 0; i < 8; i++) {
            stub.enqueueToolCalls("getDeviceSignals",
                    "{\"maskedCustomerRef\":\"" + DomainFixtures.SAMPLE_CUSTOMER + "\"}");
        }
        assertThatThrownBy(() -> agent.analyze(context))
                .isInstanceOf(LangChain4jProcessingException.class)
                .hasMessageContaining("round bound");
    }
}
