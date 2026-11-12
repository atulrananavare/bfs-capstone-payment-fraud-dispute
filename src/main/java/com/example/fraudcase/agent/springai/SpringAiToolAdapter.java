package com.example.fraudcase.agent.springai;

import com.example.fraudcase.application.EvidenceRecorder;
import com.example.fraudcase.application.ToolExecutionService;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.tools.CaseSlaTool;
import com.example.fraudcase.tools.CompareRecentLocationsTool;
import com.example.fraudcase.tools.DeviceSignalsTool;
import com.example.fraudcase.tools.EvidenceTool;
import com.example.fraudcase.tools.MerchantRiskProfileTool;
import com.example.fraudcase.tools.PriorDisputesTool;
import com.example.fraudcase.tools.ToolRequest;
import com.example.fraudcase.tools.ToolResult;
import com.example.fraudcase.tools.TransactionDetailsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes the allow-listed deterministic evidence tools to the model —
 * nothing else. Registration is explicit (no bean scanning, no @Tool
 * discovery), every callback is bound to the current case, and three
 * independent bounds apply to every call:
 *
 * <ul>
 *   <li><b>Case scope:</b> request arguments must equal the current case's
 *       own masked references. Anything else is rejected without executing —
 *       the model cannot reach another case's data.</li>
 *   <li><b>Call budget:</b> a shared counter enforces
 *       {@code capstone.ai.max-tool-calls}; excess calls get a structured
 *       {@code LIMIT_EXCEEDED} response instead of executing.</li>
 *   <li><b>Elapsed bound:</b> executions exceeding
 *       {@code capstone.tools.timeout-ms} are reported to the model as
 *       {@code TIMEOUT}; the real trace still records the true duration.</li>
 * </ul>
 *
 * <p>Every genuine execution goes through {@link ToolExecutionService} so it
 * produces the same persisted trace + audit rows as application-driven
 * calls, and its evidence items are persisted through
 * {@link EvidenceRecorder}. What is returned to the model is a sanitized
 * {@link ToolView} — status + the already-masked attributes only.
 */
@Component
public class SpringAiToolAdapter {

    private final TransactionDetailsTool transactionTool;
    private final DeviceSignalsTool deviceTool;
    private final CompareRecentLocationsTool locationTool;
    private final MerchantRiskProfileTool merchantTool;
    private final PriorDisputesTool priorDisputesTool;
    private final CaseSlaTool slaTool;
    private final ToolExecutionService toolExecution;
    private final EvidenceRecorder evidenceRecorder;
    private final long timeoutMs;
    private final int maxToolCalls;

    @org.springframework.beans.factory.annotation.Autowired
    public SpringAiToolAdapter(TransactionDetailsTool transactionTool,
                               DeviceSignalsTool deviceTool,
                               CompareRecentLocationsTool locationTool,
                               MerchantRiskProfileTool merchantTool,
                               PriorDisputesTool priorDisputesTool,
                               CaseSlaTool slaTool,
                               ToolExecutionService toolExecution,
                               EvidenceRecorder evidenceRecorder,
                               com.example.fraudcase.config.CapstoneProperties properties) {
        this(transactionTool, deviceTool, locationTool, merchantTool,
                priorDisputesTool, slaTool, toolExecution, evidenceRecorder,
                properties.getTools().getTimeoutMs(), properties.getAi().getMaxToolCalls());
    }

    /** Visible for testing — inject explicit bounds. */
    SpringAiToolAdapter(TransactionDetailsTool transactionTool,
                        DeviceSignalsTool deviceTool,
                        CompareRecentLocationsTool locationTool,
                        MerchantRiskProfileTool merchantTool,
                        PriorDisputesTool priorDisputesTool,
                        CaseSlaTool slaTool,
                        ToolExecutionService toolExecution,
                        EvidenceRecorder evidenceRecorder,
                        long timeoutMs, int maxToolCalls) {
        this.transactionTool = transactionTool;
        this.deviceTool = deviceTool;
        this.locationTool = locationTool;
        this.merchantTool = merchantTool;
        this.priorDisputesTool = priorDisputesTool;
        this.slaTool = slaTool;
        this.toolExecution = toolExecution;
        this.evidenceRecorder = evidenceRecorder;
        this.timeoutMs = timeoutMs;
        this.maxToolCalls = maxToolCalls;
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

    /** Sanitized result handed back to the model — never raw entities. */
    public record ToolView(String tool, String status,
                           Map<String, String> attributes) {
    }

    /**
     * The six — and only six — approved callbacks for one case. Each is
     * bound to {@code fraudCase}: arguments that do not match the case's own
     * masked references are rejected before execution.
     */
    public List<ToolCallback> callbacksFor(FraudCase fraudCase, String investigationId) {
        AtomicInteger budget = new AtomicInteger(maxToolCalls);
        String caseId = fraudCase.caseId();
        String txnRef = fraudCase.transaction().transactionRef();
        String custRef = fraudCase.customer().maskedCustomerRef();
        String merchant = fraudCase.transaction().maskedMerchant();

        return List.of(
                FunctionToolCallback.builder("getTransactionDetails",
                                (TransactionToolInput in) -> guarded("getTransactionDetails", budget, () ->
                                        in.transactionRef().equals(txnRef)
                                                ? execute(transactionTool,
                                                new TransactionDetailsTool.Request(in.transactionRef()),
                                                caseId, investigationId)
                                                : outOfScope("getTransactionDetails")))
                        .description("Fetch the disputed transaction record "
                                + "for the current case's transactionRef")
                        .inputType(TransactionToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getDeviceSignals",
                                (CustomerToolInput in) -> guarded("getDeviceSignals", budget, () ->
                                        in.maskedCustomerRef().equals(custRef)
                                                ? execute(deviceTool,
                                                new DeviceSignalsTool.Request(in.maskedCustomerRef()),
                                                caseId, investigationId)
                                                : outOfScope("getDeviceSignals")))
                        .description("Fetch device-trust signals for the current "
                                + "case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("compareRecentLocations",
                                (CustomerToolInput in) -> guarded("compareRecentLocations", budget, () ->
                                        in.maskedCustomerRef().equals(custRef)
                                                ? execute(locationTool,
                                                new CompareRecentLocationsTool.Request(in.maskedCustomerRef()),
                                                caseId, investigationId)
                                                : outOfScope("compareRecentLocations")))
                        .description("Compare recent activity locations for the "
                                + "current case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getMerchantRiskProfile",
                                (MerchantToolInput in) -> guarded("getMerchantRiskProfile", budget, () ->
                                        in.maskedMerchant().equals(merchant)
                                                ? execute(merchantTool,
                                                new MerchantRiskProfileTool.Request(in.maskedMerchant()),
                                                caseId, investigationId)
                                                : outOfScope("getMerchantRiskProfile")))
                        .description("Fetch the risk profile for the current "
                                + "case's masked merchant")
                        .inputType(MerchantToolInput.class)
                        .build(),
                FunctionToolCallback.builder("getPriorDisputes",
                                (CustomerToolInput in) -> guarded("getPriorDisputes", budget, () ->
                                        in.maskedCustomerRef().equals(custRef)
                                                ? execute(priorDisputesTool,
                                                new PriorDisputesTool.Request(in.maskedCustomerRef()),
                                                caseId, investigationId)
                                                : outOfScope("getPriorDisputes")))
                        .description("Fetch prior dispute history for the current "
                                + "case's masked customer reference")
                        .inputType(CustomerToolInput.class)
                        .build(),
                FunctionToolCallback.builder("calculateCaseSla",
                                (SlaToolInput in) -> guarded("calculateCaseSla", budget, () -> {
                                    com.example.fraudcase.domain.RecommendedRoute route;
                                    com.example.fraudcase.domain.RiskLevel risk;
                                    try {
                                        route = com.example.fraudcase.domain
                                                .RecommendedRoute.valueOf(in.recommendedRoute());
                                        risk = com.example.fraudcase.domain
                                                .RiskLevel.valueOf(in.riskLevel());
                                    } catch (IllegalArgumentException | NullPointerException ex) {
                                        return new ToolView("calculateCaseSla", "INVALID_REQUEST",
                                                Map.of("reason", "unknown route or risk value"));
                                    }
                                    return execute(slaTool,
                                            new CaseSlaTool.Request(route, risk, fraudCase.reportedAt()),
                                            caseId, investigationId);
                                }))
                        .description("Calculate the SLA deadline for a proposed "
                                + "route and risk level on the current case")
                        .inputType(SlaToolInput.class)
                        .build());
    }

    /** The allow-list — nothing outside these six names may be invoked. */
    public static final List<String> ALLOWED_TOOL_NAMES = List.of(
            "getTransactionDetails", "getDeviceSignals", "compareRecentLocations",
            "getMerchantRiskProfile", "getPriorDisputes", "calculateCaseSla");

    private ToolView guarded(String toolName, AtomicInteger budget,
                             java.util.function.Supplier<ToolView> call) {
        if (budget.decrementAndGet() < 0) {
            return new ToolView(toolName, "LIMIT_EXCEEDED",
                    Map.of("reason", "per-investigation tool-call budget exhausted"));
        }
        return call.get();
    }

    private ToolView outOfScope(String toolName) {
        return new ToolView(toolName, "INVALID_REQUEST",
                Map.of("reason", "argument is outside the current case scope"));
    }

    private <RQ extends ToolRequest, RS extends ToolResult> ToolView execute(
            EvidenceTool<RQ, RS> tool, RQ request, String caseId, String investigationId) {
        long started = System.nanoTime();
        ToolExecutionService.Execution execution =
                toolExecution.run(tool, request, caseId, investigationId);
        long elapsedNs = System.nanoTime() - started;
        if (execution.result() != null) {
            execution.result().toEvidence(caseId)
                    .forEach(e -> evidenceRecorder.record(e, investigationId));
        }
        ToolOutcome outcome = execution.outcome();
        if (elapsedNs > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
            return new ToolView(tool.name(), "TIMEOUT",
                    Map.of("reason", "execution exceeded the configured bound",
                            "boundMs", String.valueOf(timeoutMs)));
        }
        return new ToolView(tool.name(), outcome.status().name(), outcome.attributes());
    }
}
