package com.example.fraudcase.domain;

import java.util.Objects;

/**
 * The customer's declared claim. {@code customerStatement} is the original
 * validated customer text, preserved verbatim. It is untrusted data: it must
 * never be overwritten by generated summaries and never be treated as a
 * system instruction.
 */
public record ClaimFacts(
        IssueType issueType,
        String customerStatement,
        CardPossession cardPossession,
        MerchantRecognition merchantRecognised) {

    public ClaimFacts {
        Objects.requireNonNull(issueType, "issueType is required");
        Objects.requireNonNull(customerStatement, "customerStatement is required");
        Objects.requireNonNull(cardPossession, "cardPossession is required");
        Objects.requireNonNull(merchantRecognised, "merchantRecognised is required");
    }
}
