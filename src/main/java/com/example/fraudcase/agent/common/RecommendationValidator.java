package com.example.fraudcase.agent.common;

import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
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
    /**
     * Backwards-compatible entry point: no retrieved policy context, so any
     * citation is rejected.
     */
    public List<String> validate(FraudCaseRecommendation recommendation,
                                 String expectedCaseId,
                                 Set<String> caseEvidenceIds,
                                 Set<String> caseTraceIds,
                                 RecommendationMode mode,
                                 Set<String> knownRuleIds) {
        return validate(recommendation, expectedCaseId, caseEvidenceIds,
                caseTraceIds, mode, knownRuleIds, Set.of(),
                PolicyContextStatus.NOT_REQUESTED);
    }

    /**
     * Full validation including the policy context.
     *
     * @param allowedCitations citations derived from passages actually
     *                         retrieved for this case — the only citations
     *                         that may appear on the recommendation
     * @param policyStatus     what retrieval produced for this investigation
     * @return list of violations; empty means valid
     */
    public List<String> validate(FraudCaseRecommendation recommendation,
                                 String expectedCaseId,
                                 Set<String> caseEvidenceIds,
                                 Set<String> caseTraceIds,
                                 RecommendationMode mode,
                                 Set<String> knownRuleIds,
                                 Set<PolicyCitation> allowedCitations,
                                 PolicyContextStatus policyStatus) {
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

        // Citations must resolve to passages actually retrieved for THIS
        // case. Rules processors never cite policy; spring-ai may only cite
        // from the trusted retrieved set (exact identity + score + passage).
        if (mode == RecommendationMode.RULES
                || mode == RecommendationMode.RULES_FALLBACK) {
            if (!recommendation.policyCitations().isEmpty()) {
                violations.add("policyCitations must be empty: rules-mode "
                        + "recommendations must not carry citations");
            }
        } else {
            for (PolicyCitation citation : recommendation.policyCitations()) {
                PolicyCitation allowed = allowedCitations.stream()
                        .filter(a -> a.chunkId().equals(citation.chunkId())
                                && a.documentId().equals(citation.documentId())
                                && a.version().equals(citation.version())
                                && a.section().equals(citation.section()))
                        .findFirst().orElse(null);
                if (allowed == null) {
                    violations.add("policyCitations reference a passage that "
                            + "was not retrieved for this case: "
                            + citation.chunkId());
                } else {
                    if (Math.abs(citation.score() - allowed.score()) > 0.001) {
                        violations.add("policyCitation score does not match "
                                + "the retrieved score: " + citation.chunkId());
                    }
                    if (allowed.passage() != null && citation.passage() != null
                            && !citation.passage().equals(allowed.passage())) {
                        violations.add("policyCitation passage does not match "
                                + "the retrieved passage: "
                                + citation.chunkId());
                    }
                }
            }
        }

        // Procedural claims need grounding: citing no passage while claiming
        // policy support, or claiming any route despite missing grounding.
        String rat = recommendation.rationale() == null
                ? "" : recommendation.rationale().toLowerCase();
        if (isAiMode(mode)
                && recommendation.policyCitations().isEmpty()
                && !allowedCitations.isEmpty()
                && (rat.contains("policy") || rat.contains("procedure"))) {
            violations.add("rationale makes a procedural/policy claim without "
                    + "citing a retrieved passage");
        }
        if (isAiMode(mode)
                && (policyStatus == PolicyContextStatus.NO_MATCH
                        || policyStatus == PolicyContextStatus.UNAVAILABLE)) {
            if (!recommendation.policyCitations().isEmpty()) {
                violations.add("policyCitations impossible: no policy passage "
                        + "was retrieved for this case");
            }
            if (recommendation.recommendedRoute() != RecommendedRoute.MANUAL_REVIEW
                    && recommendation.classification()
                            != CaseClassification.INSUFFICIENT_EVIDENCE) {
                violations.add("no applicable policy context was retrieved: "
                        + "expected INSUFFICIENT_EVIDENCE or MANUAL_REVIEW "
                        + "routing");
            }
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
        } else if (isAiMode(mode)
                && !recommendation.ruleIds().isEmpty()) {
            violations.add("AI-mode output must not claim deterministic rule ids: "
                    + recommendation.ruleIds());
        }

        return violations;
    }

    private static boolean isAiMode(RecommendationMode mode) {
        return mode == RecommendationMode.SPRING_AI
                || mode == RecommendationMode.LANGCHAIN4J;
    }
}
