package com.example.fraudcase.api;

import com.example.fraudcase.api.validation.NoSensitiveContent;
import com.example.fraudcase.api.validation.NoUnsafeMarkup;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Conversational follow-up request. The message is untrusted data —
 * bounded, markup-free and free of payment/credential material.
 * {@code clientReference} is an optional idempotency key: a retry carrying
 * the same reference returns the stored exchange instead of duplicating
 * turns. Server-managed scope fields (caseId, memory ids) are deliberately
 * absent — scope comes only from the trusted path variable.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CaseMessageRequest(

        @NotBlank
        @Size(max = 2000)
        @NoUnsafeMarkup
        @NoSensitiveContent
        String message,

        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "must contain only letters, digits, '.', '_' or '-'")
        String clientReference) {
}
