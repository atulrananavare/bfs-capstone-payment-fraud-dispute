package com.example.fraudcase.api;

import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.IssueType;

import java.time.OffsetDateTime;

/**
 * Safe case summary: masked identifiers only, never raw statement text or
 * sensitive values.
 */
public record CaseSummaryResponse(
        String caseId,
        CaseState state,
        OffsetDateTime reportedAt,
        String maskedCustomerRef,
        IssueType issueType,
        String maskedMerchant) {
}
