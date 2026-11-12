package com.example.fraudcase.agent.common;

import com.example.fraudcase.application.EvidenceRecorder;
import com.example.fraudcase.application.ToolExecutionService;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
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
import com.example.fraudcase.config.CapstoneProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framework-neutral core for model-driven tool invocation — the single
 * implementation both AI adapters delegate to. Every call enforces, in
 * order:
 *
 * <ul>
 *   <li><b>Allow-list:</b> only the six approved names dispatch; anything
 *       else is answered {@code INVALID_REQUEST} without executing.</li>
 *   <li><b>Call budget:</b> {@code capstone.ai.max-tool-calls} per
 *       investigation; excess calls get {@code LIMIT_EXCEEDED}.</li>
 *   <li><b>Case scope:</b> arguments must equal the current case's own
 *       masked references — foreign refs are rejected without executing.</li>
 *   <li><b>Elapsed bound:</b> executions beyond
 *       {@code capstone.tools.timeout-ms} are reported as {@code TIMEOUT}.</li>
 * </ul>
 *
 * <p>Every genuine execution goes through {@link ToolExecutionService} so
 * it produces the same persisted trace + audit rows as application-driven
 * calls, and evidence is persisted via {@link EvidenceRecorder}. The model
 * only ever sees a sanitized {@link ToolView}.
 */
@Component
public class CaseToolInvoker {

    /** The allow-list — nothing outside these six names may be invoked. */
    public static final List<String> ALLOWED_TOOL_NAMES = List.of(
            "getTransactionDetails", "getDeviceSignals", "compareRecentLocations",
            "getMerchantRiskProfile", "getPriorDisputes", "calculateCaseSla");

    private final TransactionDetailsTool transactionTool;
    private final DeviceSignalsTool deviceTool;
    private final CompareRecentLocationsTool locationTool;
    private final MerchantRiskProfileTool merchantTool;
    private final PriorDisputesTool priorDisputesTool;
    private final CaseSlaTool slaTool;
    private final ToolExecutionService toolExecution;
    private final EvidenceRecorder evidenceRecorder;
    private final long timeoutMs;

    @org.springframework.beans.factory.annotation.Autowired
    public CaseToolInvoker(TransactionDetailsTool transactionTool,
                           DeviceSignalsTool deviceTool,
                           CompareRecentLocationsTool locationTool,
                           MerchantRiskProfileTool merchantTool,
                           PriorDisputesTool priorDisputesTool,
                           CaseSlaTool slaTool,
                           ToolExecutionService toolExecution,
                           EvidenceRecorder evidenceRecorder,
                           CapstoneProperties properties) {
        this(transactionTool, deviceTool, locationTool, merchantTool,
                priorDisputesTool, slaTool, toolExecution, evidenceRecorder,
                properties.getTools().getTimeoutMs());
    }

    /** Visible for testing — inject an explicit elapsed bound. */
    public CaseToolInvoker(TransactionDetailsTool transactionTool,
                    DeviceSignalsTool deviceTool,
                    CompareRecentLocationsTool locationTool,
                    MerchantRiskProfileTool merchantTool,
                    PriorDisputesTool priorDisputesTool,
                    CaseSlaTool slaTool,
                    ToolExecutionService toolExecution,
                    EvidenceRecorder evidenceRecorder,
                    long timeoutMs) {
        this.transactionTool = transactionTool;
        this.deviceTool = deviceTool;
        this.locationTool = locationTool;
        this.merchantTool = merchantTool;
        this.priorDisputesTool = priorDisputesTool;
        this.slaTool = slaTool;
        this.toolExecution = toolExecution;
        this.evidenceRecorder = evidenceRecorder;
        this.timeoutMs = timeoutMs;
    }

    /** Sanitized result handed back to the model — never raw entities. */
    public record ToolView(String tool, String status,
                           Map<String, String> attributes) {
    }

    /**
     * Executes one tool call on behalf of the model. {@code args} are the
     * adapter-parsed argument names/values; {@code budget} is the shared
     * per-investigation counter owned by the caller.
     */
    public ToolView invoke(String toolName, Map<String, ?> args,
                           FraudCase fraudCase, String investigationId,
                           AtomicInteger budget) {
        if (budget != null && budget.decrementAndGet() < 0) {
            return new ToolView(toolName, "LIMIT_EXCEEDED",
                    Map.of("reason", "per-investigation tool-call budget exhausted"));
        }
        String caseId = fraudCase.caseId();
        String txnRef = fraudCase.transaction().transactionRef();
        String custRef = fraudCase.customer().maskedCustomerRef();
        String merchant = fraudCase.transaction().maskedMerchant();

        return switch (toolName) {
            case "getTransactionDetails" -> {
                String ref = str(args, "transactionRef");
                yield ref != null && ref.equals(txnRef)
                        ? execute(transactionTool,
                                new TransactionDetailsTool.Request(ref), caseId, investigationId)
                        : outOfScope(toolName);
            }
            case "getDeviceSignals" -> {
                String ref = str(args, "maskedCustomerRef");
                yield ref != null && ref.equals(custRef)
                        ? execute(deviceTool,
                                new DeviceSignalsTool.Request(ref), caseId, investigationId)
                        : outOfScope(toolName);
            }
            case "compareRecentLocations" -> {
                String ref = str(args, "maskedCustomerRef");
                yield ref != null && ref.equals(custRef)
                        ? execute(locationTool,
                                new CompareRecentLocationsTool.Request(ref), caseId, investigationId)
                        : outOfScope(toolName);
            }
            case "getMerchantRiskProfile" -> {
                String ref = str(args, "maskedMerchant");
                yield ref != null && ref.equals(merchant)
                        ? execute(merchantTool,
                                new MerchantRiskProfileTool.Request(ref), caseId, investigationId)
                        : outOfScope(toolName);
            }
            case "getPriorDisputes" -> {
                String ref = str(args, "maskedCustomerRef");
                yield ref != null && ref.equals(custRef)
                        ? execute(priorDisputesTool,
                                new PriorDisputesTool.Request(ref), caseId, investigationId)
                        : outOfScope(toolName);
            }
            case "calculateCaseSla" -> {
                RecommendedRoute route;
                RiskLevel risk;
                try {
                    route = RecommendedRoute.valueOf(str(args, "recommendedRoute"));
                    risk = RiskLevel.valueOf(str(args, "riskLevel"));
                } catch (IllegalArgumentException | NullPointerException ex) {
                    yield new ToolView(toolName, "INVALID_REQUEST",
                            Map.of("reason", "unknown route or risk value"));
                }
                yield execute(slaTool,
                        new CaseSlaTool.Request(route, risk, fraudCase.reportedAt()),
                        caseId, investigationId);
            }
            default -> new ToolView(toolName, "INVALID_REQUEST",
                    Map.of("reason", "tool is not allow-listed"));
        };
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

    private static String str(Map<String, ?> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
