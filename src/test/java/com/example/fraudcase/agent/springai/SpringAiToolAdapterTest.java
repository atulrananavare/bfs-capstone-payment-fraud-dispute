package com.example.fraudcase.agent.springai;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.common.CaseToolInvoker;
import com.example.fraudcase.DomainFixtures;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.application.EvidenceRecorder;
import com.example.fraudcase.application.ToolExecutionService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.TransactionDetailsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Security and bounds on the model-facing tool surface. Tool callbacks are
 * invoked directly via {@link ToolCallback#call(String)} — no model needed.
 */
class SpringAiToolAdapterTest {

    private static final String INV = "INV-TEST01";

    private CapstoneProperties properties;
    private Clock clock;
    private ToolExecutionService toolExecution;
    private EvidenceRecorder evidenceRecorder;
    private FraudCase fraudCase;

    @BeforeEach
    void setUp() {
        properties = new CapstoneProperties();
        clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        toolExecution = new ToolExecutionService(
                mock(ToolTraceRepository.class), mock(AuditService.class), clock, TestFixtures.testMetrics());
        evidenceRecorder = new EvidenceRecorder(mock(EvidenceItemRepository.class));
        fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
    }

    private SpringAiToolAdapter adapter(long timeoutMs, int maxToolCalls) {
        CaseToolInvoker invoker = new CaseToolInvoker(
                new TransactionDetailsTool(properties, clock),
                new DeviceSignalsTool(properties, clock),
                new CompareRecentLocationsTool(properties, clock),
                new MerchantRiskProfileTool(properties, clock),
                new PriorDisputesTool(properties, clock),
                new CaseSlaTool(properties, clock),
                toolExecution, evidenceRecorder, timeoutMs);
        properties.getAi().setMaxToolCalls(maxToolCalls);
        return new SpringAiToolAdapter(invoker, properties);
    }

    private ToolCallback callback(List<ToolCallback> callbacks, String name) {
        return callbacks.stream()
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void exactlyTheSixAllowListedToolsAreRegistered() {
        List<ToolCallback> callbacks = adapter(2500, 6).callbacksFor(fraudCase, INV);
        assertThat(callbacks.stream().map(c -> c.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrderElementsOf(SpringAiToolAdapter.ALLOWED_TOOL_NAMES);
        assertThat(SpringAiToolAdapter.ALLOWED_TOOL_NAMES)
                .containsExactlyInAnyOrder(
                        "getTransactionDetails", "getDeviceSignals",
                        "compareRecentLocations", "getMerchantRiskProfile",
                        "getPriorDisputes", "calculateCaseSla");
    }

    @Test
    void inScopeCallExecutesAndReturnsStructuredResult() {
        List<ToolCallback> callbacks = adapter(2500, 6).callbacksFor(fraudCase, INV);
        String json = callback(callbacks, "getTransactionDetails")
                .call("{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}");
        assertThat(json).contains("SUCCESS").contains("getTransactionDetails");
    }

    @Test
    void foreignTransactionReferenceIsRejectedWithoutExecuting() {
        List<ToolCallback> callbacks = adapter(2500, 6).callbacksFor(fraudCase, INV);
        String json = callback(callbacks, "getTransactionDetails")
                .call("{\"transactionRef\":\"TXN-9999-OTHERCASE\"}");
        assertThat(json).contains("INVALID_REQUEST");
    }

    @Test
    void foreignCustomerReferenceIsRejected() {
        List<ToolCallback> callbacks = adapter(2500, 6).callbacksFor(fraudCase, INV);
        String json = callback(callbacks, "getDeviceSignals")
                .call("{\"maskedCustomerRef\":\"CUST-••••-9999\"}");
        assertThat(json).contains("INVALID_REQUEST");
    }

    @Test
    void toolCallBudgetIsEnforced() {
        List<ToolCallback> callbacks = adapter(2500, 2).callbacksFor(fraudCase, INV);
        ToolCallback tx = callback(callbacks, "getTransactionDetails");
        String args = "{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}";
        assertThat(tx.call(args)).contains("SUCCESS");
        assertThat(tx.call(args)).contains("SUCCESS");
        assertThat(tx.call(args)).contains("LIMIT_EXCEEDED");
    }

    @Test
    void exceededTimeoutIsReportedAsStructuredFailure() {
        // timeoutMs = 0 → every execution exceeds the bound.
        List<ToolCallback> callbacks = adapter(0, 6).callbacksFor(fraudCase, INV);
        String json = callback(callbacks, "getTransactionDetails")
                .call("{\"transactionRef\":\"" + DomainFixtures.SAMPLE_TXN + "\"}");
        assertThat(json).contains("TIMEOUT");
    }

    @Test
    void invalidSlaEnumInputIsRejected() {
        List<ToolCallback> callbacks = adapter(2500, 6).callbacksFor(fraudCase, INV);
        String json = callback(callbacks, "calculateCaseSla")
                .call("{\"recommendedRoute\":\"BLOCK_THE_CARD\",\"riskLevel\":\"HIGH\"}");
        assertThat(json).contains("INVALID_REQUEST");
    }
}
