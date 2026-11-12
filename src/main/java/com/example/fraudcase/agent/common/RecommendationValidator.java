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
 *
 * <p>Mode-aware rules (Phase 3):
 * <ul>
 *   <li>{@code RULES} — must expose at least one known deterministic rule id.</li>
 *   <li>{@code RULES_FALLBACK} — same rule contract as {@code RULES}; the
 *       caller passes the rule ids the fallback may legitimately emit.</li>
 *   <li>{@code SPRING_AI} — must NOT claim deterministic rule ids (the model
 *       did not run the rules engine) and must not carry policy citations
 *       (no retrieval exists in this phase).</li>
 * </ul>
 * Rationale is bounded for every mode and must never claim that an
 * autonomous financial, account-control or customer-contact action was
 * executed.
 */
@Component
public class RecommendationValidator {

    /** Matches the {@code recommendation.rationale} column bound. */
    public static final int MAX_RATIONALE_LENGTH = 2000;

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

        String rationale = recommendation.rationale();
        if (rationale == null || rationale.isBlank()) {
            violations.add("rationale is required");
        } else {
            if (rationale.length() > MAX_RATIONALE_LENGTH) {
                violations.add("rationale exceeds " + MAX_RATIONALE_LENGTH + " characters");
            }
            if (ActionClaimGuard.claimsExecutedAction(rationale)) {
                violations.add("rationale claims an executed autonomous action, "
                        + "which is permanently out of scope");
            }
        }

        // No retrieval exists in this phase: citations can never be
        // legitimate regardless of which processor produced the output.
        if (!recommendation.policyCitations().isEmpty()) {
            violations.add("policyCitations must be empty: no policy retrieval "
                    + "exists in this phase");
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

        if (mode == RecommendationMode.RULES || mode == RecommendationMode.RULES_FALLBACK) {
            if (recommendation.ruleIds().isEmpty()) {
                violations.add("rules-mode recommendation must expose at least one rule id");
            }
            Set<String> unknown = new LinkedHashSet<>(recommendation.ruleIds());
            unknown.removeAll(knownRuleIds);
            if (!unknown.isEmpty()) {
                violations.add("unknown rule ids for rules mode: " + unknown);
            }
        } else if (mode == RecommendationMode.SPRING_AI
                && !recommendation.ruleIds().isEmpty()) {
            violations.add("spring-ai output must not claim deterministic rule ids: "
                    + recommendation.ruleIds());
        }

        return violations;
    }
}
