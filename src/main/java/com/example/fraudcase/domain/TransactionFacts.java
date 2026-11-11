package com.example.fraudcase.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Disputed transaction facts captured at intake. Only masked/synthetic
 * merchant values are stored.
 */
public record TransactionFacts(
        String transactionRef,
        BigDecimal amount,
        String currency,
        OffsetDateTime occurredAt,
        TransactionStatus status,
        TransactionChannel channel,
        String maskedMerchant) {

    public TransactionFacts {
        Objects.requireNonNull(transactionRef, "transactionRef is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(channel, "channel is required");
        Objects.requireNonNull(maskedMerchant, "maskedMerchant is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
