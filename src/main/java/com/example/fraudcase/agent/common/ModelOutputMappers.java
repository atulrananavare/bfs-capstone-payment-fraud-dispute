package com.example.fraudcase.agent.common;

import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.FollowUpAnswer;
import com.example.fraudcase.domain.FollowUpContext;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyPassage;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared, framework-neutral mapping from validated-JSON model output to
 * domain objects — used identically by every AI adapter so the safety
 * contract is enforced once:
 *
 * <ul>
 *   <li>a {@code caseId} naming a different case invalidates the output;</li>
 *   <li>rationale/answers echoing system-prompt material are rejected;</li>
 *   <li>citation ids resolve only against passages actually retrieved for
 *       this case — every field comes from the trusted source;</li>
 *   <li>follow-up answers may only cite this case's persisted evidence,
 *       never claim executed actions and never relax human review.</li>
 * </ul>
 */
public final class ModelOutputMappers {

    static final int MAX_ANSWER_LENGTH = 4000;

    private ModelOutputMappers() {
    }

    public static FraudCaseRecommendation toRecommendation(
            AiRecommendationOutput out, FraudCaseContext context,
            List<String> promptLeakMarkers) {
        if (out.caseId() != null && !out.caseId().isBlank()
                && !out.caseId().equals(context.caseId())) {
            // The model claimed a different case — its entire output is
            // untrusted, not silently re-scoped.
            throw new InvalidModelOutputException(
                    "model output caseId does not match the investigated case");
        }
        List<PolicyCitation> citations =
                resolveCitations(out.policyCitations(), context);
        if (out.rationale() != null && promptLeakMarkers.stream()
                .anyMatch(out.rationale()::contains)) {
            // Parroting system-prompt material is not a rationale.
            throw new InvalidModelOutputException(
                    "rationale contains system-prompt material");
        }
        CaseClassification classification = parseEnum(
                CaseClassification.class, out.classification(), "classification");
        RiskLevel risk = parseEnum(RiskLevel.class, out.riskLevel(), "riskLevel");
        RecommendedRoute route = parseEnum(
                RecommendedRoute.class, out.recommendedRoute(), "recommendedRoute");
        double confidence = out.confidence() == null ? -1 : out.confidence();
        boolean humanReview = out.humanReviewRequired() != null
                && out.humanReviewRequired();

        try {
            return new FraudCaseRecommendation(
                    context.caseId(), classification, risk, route,
                    confidence, humanReview,
                    bounded(out.evidenceIds()),
                    citations,
                    bounded(out.missingInformation()),
                    List.of(), // ruleIds — the model never ran the rules engine
                    bounded(out.toolTraceIds()),
                    out.rationale() == null ? "" : out.rationale());
        } catch (IllegalArgumentException | NullPointerException ex) {
            // Domain contract violations — including requireNonNull on a
            // missing mandatory field — are invalid output, not invocation
            // failures.
            throw new InvalidModelOutputException(
                    "model output violates the recommendation contract: "
                            + ex.getMessage(), ex);
        }
    }

    public static FollowUpAnswer toFollowUpAnswer(
            AiFollowUpOutput out, FollowUpContext context,
            List<String> promptLeakMarkers) {
        if (out.caseId() != null && !out.caseId().isBlank()
                && !out.caseId().equals(context.caseId())) {
            // The model claimed a different case — untrusted, not re-scoped.
            throw new InvalidModelOutputException(
                    "follow-up output caseId does not match the current case");
        }
        if (Boolean.FALSE.equals(out.humanReviewRequired())) {
            throw new InvalidModelOutputException(
                    "humanReviewRequired must always be true");
        }
        String answer = out.answer() == null ? "" : out.answer().strip();
        if (answer.isBlank()) {
            throw new InvalidModelOutputException("follow-up answer is empty");
        }
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw new InvalidModelOutputException(
                    "follow-up answer exceeds the configured bound");
        }
        if (promptLeakMarkers.stream().anyMatch(answer::contains)) {
            throw new InvalidModelOutputException(
                    "answer contains system-prompt material");
        }
        if (ActionClaimGuard.claimsExecutedAction(answer)) {
            throw new InvalidModelOutputException(
                    "answer claims an executed autonomous action, which is "
                            + "permanently out of scope");
        }
        List<String> evidenceIds = validateEvidenceIds(out.evidenceIds(), context);
        List<PolicyCitation> citations =
                resolveCitations(out.policyCitations(), context);
        return new FollowUpAnswer(answer, evidenceIds, citations,
                bounded(out.missingInformation()), true);
    }

    /**
     * Resolve model-supplied chunk ids against the passages actually
     * retrieved for THIS investigation. Every citation field comes from the
     * trusted {@link PolicyPassage}; an unknown or foreign chunk id means
     * the model fabricated it, so the whole output is invalid.
     */
    private static List<PolicyCitation> resolveCitations(
            List<String> citedChunkIds, FraudCaseContext context) {
        if (citedChunkIds == null || citedChunkIds.isEmpty()) {
            return List.of();
        }
        List<PolicyCitation> resolved = new ArrayList<>();
        for (String chunkId : citedChunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            PolicyPassage passage = context.policyPassages().stream()
                    .filter(p -> p.chunkId().equals(chunkId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidModelOutputException(
                            "model cited a policy passage that was not "
                                    + "retrieved for this case: " + chunkId));
            resolved.add(PolicyCitation.from(passage));
        }
        return List.copyOf(resolved);
    }

    private static List<String> validateEvidenceIds(List<String> ids,
                                                    FollowUpContext context) {
        List<String> clean = bounded(ids);
        List<String> caseEvidence = context.evidence().stream()
                .map(EvidenceItem::evidenceId).toList();
        for (String id : clean) {
            if (!caseEvidence.contains(id)) {
                throw new InvalidModelOutputException(
                        "answer references evidence that does not belong to "
                                + "this case: " + id);
            }
        }
        return List.copyOf(clean);
    }

    /**
     * A citation id resolves only against passages retrieved this turn or
     * citations already validated and persisted for THIS case. Every field
     * is taken from the trusted source — never from model output.
     */
    private static List<PolicyCitation> resolveCitations(
            List<String> citedChunkIds, FollowUpContext context) {
        if (citedChunkIds == null || citedChunkIds.isEmpty()) {
            return List.of();
        }
        Map<String, PolicyCitation> trusted = new LinkedHashMap<>();
        for (PolicyPassage p : context.policyPassages()) {
            trusted.put(p.chunkId(), PolicyCitation.from(p));
        }
        if (context.latestRecommendation() != null) {
            for (PolicyCitation c : context.latestRecommendation().policyCitations()) {
                trusted.putIfAbsent(c.chunkId(), c);
            }
        }
        List<PolicyCitation> resolved = new ArrayList<>();
        for (String chunkId : citedChunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            PolicyCitation citation = trusted.get(chunkId);
            if (citation == null) {
                throw new InvalidModelOutputException(
                        "model cited a policy passage that is not valid for "
                                + "this case: " + chunkId);
            }
            resolved.add(citation);
        }
        return List.copyOf(resolved);
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String value, String field) {
        try {
            return value == null ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidModelOutputException(
                    "unknown " + field + " value: " + value, ex);
        }
    }

    private static List<String> bounded(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }
}
