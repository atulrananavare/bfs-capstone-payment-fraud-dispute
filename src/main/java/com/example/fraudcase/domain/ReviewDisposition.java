package com.example.fraudcase.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Immutable record of a human reviewer's disposition of a specific validated
 * recommendation. Stored separately from {@link FraudCaseRecommendation}:
 * the advisory recommendation is never rewritten to match the human
 * decision, and this record never implies a banking action executed.
 */
public record ReviewDisposition(
        String reviewId,
        String caseId,
        String recommendationId,
        String reviewerId,
        ReviewDispositionType disposition,
        String reason,
        RecommendedRoute targetRoute,
        boolean evidenceAcknowledged,
        boolean policyAcknowledged,
        boolean missingInformationAcknowledged,
        boolean contradictionsAcknowledged,
        OffsetDateTime recordedAt) {

    public ReviewDisposition {
        Objects.requireNonNull(reviewId, "reviewId is required");
        Objects.requireNonNull(caseId, "caseId is required");
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(reviewerId, "reviewerId is required");
        Objects.requireNonNull(disposition, "disposition is required");
        Objects.requireNonNull(reason, "reason is required");
        Objects.requireNonNull(recordedAt, "recordedAt is required");
    }
}
