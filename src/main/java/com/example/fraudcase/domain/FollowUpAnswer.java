package com.example.fraudcase.domain;

import java.util.List;
import java.util.Objects;

/**
 * A validated conversational answer for one case. Distinct from
 * {@link FraudCaseRecommendation}: this is not a recommendation and carries
 * no authority — it is a grounded, advisory response to a question.
 * {@code humanReviewRequired} is always {@code true}.
 */
public record FollowUpAnswer(
        String answer,
        List<String> evidenceIds,
        List<PolicyCitation> policyCitations,
        List<String> missingInformation,
        boolean humanReviewRequired) {

    public FollowUpAnswer {
        Objects.requireNonNull(answer, "answer is required");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        policyCitations = policyCitations == null
                ? List.of() : List.copyOf(policyCitations);
        missingInformation = missingInformation == null
                ? List.of() : List.copyOf(missingInformation);
        if (!humanReviewRequired) {
            throw new IllegalArgumentException(
                    "humanReviewRequired must always be true");
        }
    }
}
