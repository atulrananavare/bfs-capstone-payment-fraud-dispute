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
        List<String> toolTraceIds,
        String investigationId,
        List<PolicyPassage> policyPassages,
        PolicyContextStatus policyStatus) {

    public FraudCaseContext {
        Objects.requireNonNull(fraudCase, "fraudCase is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
        toolTraceIds = toolTraceIds == null ? List.of() : List.copyOf(toolTraceIds);
        policyPassages = policyPassages == null ? List.of() : List.copyOf(policyPassages);
        policyStatus = policyStatus == null ? PolicyContextStatus.NOT_REQUESTED : policyStatus;
    }

    public FraudCaseContext(FraudCase fraudCase, List<EvidenceItem> evidence,
                            List<ToolOutcome> toolOutcomes, List<String> toolTraceIds,
                            String investigationId) {
        this(fraudCase, evidence, toolOutcomes, toolTraceIds, investigationId,
                List.of(), PolicyContextStatus.NOT_REQUESTED);
    }

    public FraudCaseContext(FraudCase fraudCase, List<EvidenceItem> evidence,
                            List<ToolOutcome> toolOutcomes, List<String> toolTraceIds) {
        this(fraudCase, evidence, toolOutcomes, toolTraceIds, null);
    }

    public FraudCaseContext(FraudCase fraudCase) {
        this(fraudCase, List.of(), List.of(), List.of(), null);
    }

    /** Returns a copy carrying the retrieval outcome for this investigation. */
    public FraudCaseContext withPolicy(List<PolicyPassage> passages,
                                       PolicyContextStatus status) {
        return new FraudCaseContext(fraudCase, evidence, toolOutcomes,
                toolTraceIds, investigationId, passages, status);
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
