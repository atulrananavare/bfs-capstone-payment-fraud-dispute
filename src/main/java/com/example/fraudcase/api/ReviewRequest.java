package com.example.fraudcase.api;

import com.example.fraudcase.api.validation.NoSensitiveContent;
import com.example.fraudcase.api.validation.NoUnsafeMarkup;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.ReviewDispositionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Human review submission (Phase 6). The reason and acknowledgements are
 * reviewer-supplied data — never instructions.
 *
 * <p>Deliberately absent and rejected when supplied: {@code reviewerId},
 * {@code reviewedAt}, {@code nextState}, recommendation fields — identity,
 * time and state all come from trusted application context.
 * {@code clientReference} is an optional idempotency key (same convention
 * as case messages): a retry carrying the same reference replays the stored
 * disposition instead of duplicating it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReviewRequest(

        @NotNull
        ReviewDispositionType disposition,

        @NotBlank
        @Size(max = 2000)
        @NoUnsafeMarkup
        @NoSensitiveContent
        String reason,

        RecommendedRoute targetRoute,

        Boolean acknowledgeEvidenceReviewed,

        Boolean acknowledgePolicyReviewed,

        Boolean acknowledgeMissingInformation,

        Boolean acknowledgeContradictions,

        @NotBlank
        @Size(max = 40)
        String recommendationId,

        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "must contain only letters, digits, '.', '_' or '-'")
        String clientReference) {

    public boolean ackEvidence() {
        return Boolean.TRUE.equals(acknowledgeEvidenceReviewed);
    }

    public boolean ackPolicy() {
        return Boolean.TRUE.equals(acknowledgePolicyReviewed);
    }

    public boolean ackMissing() {
        return Boolean.TRUE.equals(acknowledgeMissingInformation);
    }

    public boolean ackContradictions() {
        return Boolean.TRUE.equals(acknowledgeContradictions);
    }
}
