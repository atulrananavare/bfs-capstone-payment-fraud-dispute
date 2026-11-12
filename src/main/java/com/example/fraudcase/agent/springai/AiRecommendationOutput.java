package com.example.fraudcase.agent.springai;

import java.util.List;

/**
 * Structured-output DTO for the Spring AI adapter only — never persisted,
 * never returned by the API. {@code BeanOutputConverter} binds model JSON to
 * this shape; {@code SpringAiFraudCaseAgent} then maps it to the
 * framework-neutral {@code FraudCaseRecommendation}.
 *
 * <p>{@code ruleIds} is deliberately absent: the model did not run the
 * rules engine, so it may never claim deterministic rule ids.
 * {@code policyCitations} is always rejected when non-empty — no policy
 * retrieval exists in this phase.
 */
public record AiRecommendationOutput(
        String caseId,
        String classification,
        String riskLevel,
        String recommendedRoute,
        Double confidence,
        Boolean humanReviewRequired,
        List<String> evidenceIds,
        List<String> missingInformation,
        List<String> toolTraceIds,
        List<String> policyCitations,
        String rationale) {
}
