package com.example.fraudcase.agent.langchain4j;

import com.example.fraudcase.agent.common.CaseToolInvoker;
import com.example.fraudcase.agent.common.CaseToolInvoker.ToolView;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.FraudCase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes the allow-listed deterministic evidence tools to the LangChain4j
 * model — nothing else. Registration is explicit (no bean scanning, no
 * {@code @Tool} discovery) and every executor is bound to the current case.
 *
 * <p>Same contract as the Spring AI adapter: this class is only the
 * framework-facing shell (tool specifications + JSON-argument parsing).
 * All scope validation, execution, tracing, evidence persistence, timeout
 * and allow-list semantics live in {@link CaseToolInvoker} — identical
 * business rules, zero duplication.
 */
@Component
public class LangChain4jToolAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CaseToolInvoker invoker;
    private final int maxToolCalls;

    public LangChain4jToolAdapter(CaseToolInvoker invoker,
                                CapstoneProperties properties) {
        this.invoker = invoker;
        this.maxToolCalls = properties.getAi().getMaxToolCalls();
    }

    /** A tool specification paired with its case-bound executor. */
    public record BoundTool(ToolSpecification specification,
                            ToolExecutor executor) {
    }

    /**
     * The six — and only six — approved tools for one case. The returned
     * executors share a per-investigation call budget (same semantics as
     * the Spring AI adapter).
     */
    public List<BoundTool> toolsFor(FraudCase fraudCase, String investigationId) {
        AtomicInteger budget = new AtomicInteger(maxToolCalls);
        return List.of(
                bound("getTransactionDetails",
                        "Fetch the disputed transaction record for the "
                                + "current case's transactionRef",
                        schema("transactionRef", "The case's transaction reference"),
                        fraudCase, investigationId, budget),
                bound("getDeviceSignals",
                        "Fetch device-trust signals for the current case's "
                                + "masked customer reference",
                        schema("maskedCustomerRef", "The case's masked customer reference"),
                        fraudCase, investigationId, budget),
                bound("compareRecentLocations",
                        "Compare recent activity locations for the current "
                                + "case's masked customer reference",
                        schema("maskedCustomerRef", "The case's masked customer reference"),
                        fraudCase, investigationId, budget),
                bound("getMerchantRiskProfile",
                        "Fetch the risk profile for the current case's "
                                + "masked merchant",
                        schema("maskedMerchant", "The case's masked merchant"),
                        fraudCase, investigationId, budget),
                bound("getPriorDisputes",
                        "Fetch prior dispute history for the current case's "
                                + "masked customer reference",
                        schema("maskedCustomerRef", "The case's masked customer reference"),
                        fraudCase, investigationId, budget),
                bound("calculateCaseSla",
                        "Calculate the SLA deadline for a proposed route and "
                                + "risk level on the current case",
                        JsonObjectSchema.builder()
                                .addStringProperty("recommendedRoute",
                                        "Proposed route enum value")
                                .addStringProperty("riskLevel",
                                        "Proposed risk level enum value")
                                .required("recommendedRoute", "riskLevel")
                                .build(),
                        fraudCase, investigationId, budget));
    }

    /** The allow-list — owned by {@link CaseToolInvoker}, re-exported here. */
    public static final List<String> ALLOWED_TOOL_NAMES = CaseToolInvoker.ALLOWED_TOOL_NAMES;

    private BoundTool bound(String name, String description,
                            JsonObjectSchema parameters, FraudCase fraudCase,
                            String investigationId, AtomicInteger budget) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
        ToolExecutor executor = (request, memoryId) -> {
            if (!name.equals(request.name())) {
                return toJson(new ToolView(name, "INVALID_REQUEST",
                        Map.of("reason", "tool name mismatch")));
            }
            Map<String, Object> args = parseArgs(request.arguments());
            ToolView view = invoker.invoke(name, args, fraudCase,
                    investigationId, budget);
            return toJson(view);
        };
        return new BoundTool(spec, executor);
    }

    private static JsonObjectSchema schema(String property, String propertyDescription) {
        return JsonObjectSchema.builder()
                .addStringProperty(property, propertyDescription)
                .required(property)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(arguments,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String toJson(ToolView view) {
        try {
            return MAPPER.writeValueAsString(view);
        } catch (Exception ex) {
            return "{\"tool\":\"" + view.tool()
                    + "\",\"status\":\"INVALID_REQUEST\",\"attributes\":{}}";
        }
    }
}
