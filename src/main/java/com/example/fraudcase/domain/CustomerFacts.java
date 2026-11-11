package com.example.fraudcase.domain;

import java.util.Objects;

/**
 * Customer facts captured at intake. The customer reference must always be
 * masked/tokenised; raw account or card identifiers are never permitted.
 */
public record CustomerFacts(
        String maskedCustomerRef,
        String homeRegion) {

    public CustomerFacts {
        Objects.requireNonNull(maskedCustomerRef, "maskedCustomerRef is required");
        Objects.requireNonNull(homeRegion, "homeRegion is required");
    }
}
