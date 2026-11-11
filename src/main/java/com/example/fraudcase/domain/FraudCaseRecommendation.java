package com.example.fraudcase.domain;

import java.util.List;
import java.util.Objects;

/**
 * Framework-neutral advisory output of a {@code FraudCaseProcessor}.
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>{@code humanReviewRequired} is always {@code true} — the application is
 *       an investigation assistant, never an autonomous decision maker.</li>
 *   <li>{@code confidence} is within [0, 1].</li>
 *   <li>{@code caseId} is present and non-blank.</li>
 * </ul>
 *
 * <p>Only a concise rationale may be exposed. Hidden model chain-of-thought
 * is never part of this contract.
 */
public record FraudCaseRecommendation(
        String caseId,
        CaseClassification classification,
        RiskLevel riskLevel,
        RecommendedRoute recommendedRoute,
        double confidence,
        boolean humanReviewRequired,
        List<String> evidenceIds,
        List<PolicyCitation> policyCitations,
        List<String> missingInformation,
        List<String> ruleIds,
        List<String> toolTraceIds,
        String rationale) {

    public FraudCaseRecommendation {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(riskLevel, "riskLevel is required");
        Objects.requireNonNull(recommendedRoute, "recommendedRoute is required");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1 inclusive");
        }
        if (!humanReviewRequired) {
            throw new IllegalArgumentException("humanReviewRequired must always be true");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        policyCitations = policyCitations == null ? List.of() : List.copyOf(policyCitations);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        toolTraceIds = toolTraceIds == null ? List.of() : List.copyOf(toolTraceIds);
        Objects.requireNonNull(rationale, "rationale is required");
    }
}
