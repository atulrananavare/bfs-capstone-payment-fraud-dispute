package com.example.fraudcase.agent.common;

import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.RecommendationMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Framework-neutral validation for processor output. A recommendation that
 * fails validation must never be persisted as an approved recommendation.
 */
@Component
public class RecommendationValidator {

    /**
     * @param recommendation    the processor output to check
     * @param expectedCaseId    the case the recommendation must belong to
     * @param caseEvidenceIds   evidence ids that exist for this case
     * @param caseTraceIds      tool-trace ids that exist for this case
     * @param mode              which processor produced the output
     * @param knownRuleIds      rule ids the mode may legitimately emit
     * @return list of violations; empty means valid
     */
    public List<String> validate(FraudCaseRecommendation recommendation,
                                 String expectedCaseId,
                                 Set<String> caseEvidenceIds,
                                 Set<String> caseTraceIds,
                                 RecommendationMode mode,
                                 Set<String> knownRuleIds) {
        List<String> violations = new ArrayList<>();
        if (recommendation == null) {
            violations.add("recommendation is null");
            return violations;
        }

        if (recommendation.caseId() == null || recommendation.caseId().isBlank()) {
            violations.add("caseId is required");
        } else if (!recommendation.caseId().equals(expectedCaseId)) {
            violations.add("caseId does not match the current case");
        }

        if (recommendation.classification() == null) {
            violations.add("classification is required");
        }
        if (recommendation.riskLevel() == null) {
            violations.add("riskLevel is required");
        }
        if (recommendation.recommendedRoute() == null) {
            violations.add("recommendedRoute is required");
        }
        if (recommendation.confidence() < 0.0 || recommendation.confidence() > 1.0) {
            violations.add("confidence must be between 0 and 1 inclusive");
        }
        if (!recommendation.humanReviewRequired()) {
            violations.add("humanReviewRequired must always be true");
        }

        boolean insufficientEvidence =
                recommendation.classification() == CaseClassification.INSUFFICIENT_EVIDENCE;
        if (recommendation.evidenceIds().isEmpty() && !insufficientEvidence) {
            violations.add("recommendation must reference at least one evidence item "
                    + "or explicitly be INSUFFICIENT_EVIDENCE");
        }

        Set<String> evidence = new LinkedHashSet<>(recommendation.evidenceIds());
        evidence.removeAll(caseEvidenceIds);
        if (!evidence.isEmpty()) {
            violations.add("evidenceIds reference evidence that does not belong to this case: "
                    + evidence);
        }

        Set<String> traces = new LinkedHashSet<>(recommendation.toolTraceIds());
        traces.removeAll(caseTraceIds);
        if (!traces.isEmpty()) {
            violations.add("toolTraceIds reference traces that do not belong to this case: "
                    + traces);
        }

        if (mode == RecommendationMode.RULES) {
            if (recommendation.ruleIds().isEmpty()) {
                violations.add("rules-mode recommendation must expose at least one rule id");
            }
            Set<String> unknown = new LinkedHashSet<>(recommendation.ruleIds());
            unknown.removeAll(knownRuleIds);
            if (!unknown.isEmpty()) {
                violations.add("unknown rule ids for rules mode: " + unknown);
            }
        }

        return violations;
    }
}
