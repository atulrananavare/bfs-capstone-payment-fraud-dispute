package com.example.fraudcase.agent.springai;

import com.example.fraudcase.agent.common.CaseToolInvoker;
import com.example.fraudcase.agent.common.CaseToolInvoker.ToolView;
import com.example.fraudcase.domain.FraudCase;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes the allow-listed deterministic evidence tools to the Spring AI
 * model — nothing else. Registration is explicit (no bean scanning, no
 * {@code @Tool} discovery) and every callback is bound to the current case.
 *
 * <p>This class is only the framework-facing shell: typed input records,
 * {@link ToolCallback} wiring and the per-investigation call budget. All
 * scope validation, execution, tracing, evidence persistence, timeout and
 * allow-list semantics live in {@link CaseToolInvoker}, shared with every
 * AI adapter so no business rule is duplicated per framework.
 */
@Component
public class SpringAiToolAdapter {

    private final CaseToolInvoker invoker;
    private final int maxToolCalls;

    public SpringAiToolAdapter(CaseToolInvoker invoker,
                               com.example.fraudcase.config.CapstoneProperties properties) {
        this.invoker = invoker;
        this.maxToolCalls = properties.getAi().getMaxToolCalls();
    }

    /** Model-facing tool inputs — one field each, JSON-mapped by Spring AI. */
    public record TransactionToolInput(String transactionRef) {
    }

    public record CustomerToolInput(String maskedCustomerRef) {
    }

    public record MerchantToolInput(String maskedMerchant) {
    }

    public record SlaToolInput(String recommendedRoute, String riskLevel) {
    }

    /**
     * The six — and only six — approved callbacks for one case. Each is
     * bound to {@code fraudCase}; the shared invoker rejects arguments
     * outside the case's own masked references before execution.
     */
    public List<ToolCallback> callbacksFor(FraudCase fraudCase, String investigationId) {
        AtomicInteger budget = new AtomicInteger(maxToolCalls);

        return List.of(
                FunctionToolCallback.builder("getTransactionDetails",
                                (TransactionToolInput in) -> invoker.invoke(
                                        "getTransactionDetails",
                                        Map.of("transactionRef", in.transactionRef()),
                                        fraudCase, investigationId, budget))
                        .description("Fetch the disputed transaction record "
                                + "for the current case's transactionRef")
                        .inputType(TransactionToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getDeviceSignals",
                                (CustomerToolInput in) -> invoker.invoke(
                                        "getDeviceSignals",
                                        Map.of("maskedCustomerRef", in.maskedCustomerRef()),
                                        fraudCase, investigationId, budget))
                        .description("Fetch device-trust signals for the current "
                                + "case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("compareRecentLocations",
                                (CustomerToolInput in) -> invoker.invoke(
                                        "compareRecentLocations",
                                        Map.of("maskedCustomerRef", in.maskedCustomerRef()),
                                        fraudCase, investigationId, budget))
                        .description("Compare recent activity locations for the "
                                + "current case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getMerchantRiskProfile",
                                (MerchantToolInput in) -> invoker.invoke(
                                        "getMerchantRiskProfile",
                                        Map.of("maskedMerchant", in.maskedMerchant()),
                                        fraudCase, investigationId, budget))
                        .description("Fetch the risk profile for the current "
                                + "case's masked merchant")
                        .inputType(MerchantToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getPriorDisputes",
                                (CustomerToolInput in) -> invoker.invoke(
                                        "getPriorDisputes",
                                        Map.of("maskedCustomerRef", in.maskedCustomerRef()),
                                        fraudCase, investigationId, budget))
                        .description("Fetch prior dispute history for the current "
                                + "case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("calculateCaseSla",
                                (SlaToolInput in) -> invoker.invoke(
                                        "calculateCaseSla",
                                        Map.of("recommendedRoute", in.recommendedRoute(),
                                                "riskLevel", in.riskLevel()),
                                        fraudCase, investigationId, budget))
                        .description("Calculate the SLA deadline for a proposed "
                                + "route and risk level on the current case")
                        .inputType(SlaToolInput.class)
                        .build());
    }

    /** The allow-list — owned by {@link CaseToolInvoker}, re-exported here. */
    public static final List<String> ALLOWED_TOOL_NAMES = CaseToolInvoker.ALLOWED_TOOL_NAMES;
}
