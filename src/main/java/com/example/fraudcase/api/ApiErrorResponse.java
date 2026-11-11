package com.example.fraudcase.api;

import java.time.Instant;
import java.util.List;

/**
 * Structured, safe error payload. Never contains stack traces, internal
 * provider details or sensitive values.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<FieldError> fieldErrors) {

    public ApiErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public record FieldError(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String error, String message) {
        return new ApiErrorResponse(Instant.now(), status, error, message, List.of());
    }

    public static ApiErrorResponse of(int status, String error, String message, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, fieldErrors);
    }
}
