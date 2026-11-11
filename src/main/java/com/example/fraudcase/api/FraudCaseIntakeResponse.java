package com.example.fraudcase.api;

import com.example.fraudcase.domain.CaseState;

import java.time.OffsetDateTime;

/**
 * Returned after a case passes intake validation and is captured.
 */
public record FraudCaseIntakeResponse(
        String caseId,
        CaseState state,
        OffsetDateTime createdAt,
        String analystId) {
}
