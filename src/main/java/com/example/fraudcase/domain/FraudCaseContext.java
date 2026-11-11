package com.example.fraudcase.domain;

import java.util.List;
import java.util.Objects;

/**
 * Framework-neutral input handed to a {@code FraudCaseProcessor}.
 *
 * <p>Carries the accepted case plus the evidence, tool outcomes and trace
 * references collected for the current investigation. A processor may only
 * use what is provided here — it must never call tools, touch state or
 * fabricate inputs.
 */
public record FraudCaseContext(
        FraudCase fraudCase,
        List<EvidenceItem> evidence,
        List<ToolOutcome> toolOutcomes,
        List<String> toolTraceIds) {

    public FraudCaseContext {
        Objects.requireNonNull(fraudCase, "fraudCase is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
        toolTraceIds = toolTraceIds == null ? List.of() : List.copyOf(toolTraceIds);
    }

    public FraudCaseContext(FraudCase fraudCase) {
        this(fraudCase, List.of(), List.of(), List.of());
    }

    public String caseId() {
        return fraudCase.caseId();
    }

    public ToolOutcome outcomeOf(String toolName) {
        return toolOutcomes.stream()
                .filter(o -> o.toolName().equals(toolName))
                .findFirst()
                .orElse(null);
    }
}
