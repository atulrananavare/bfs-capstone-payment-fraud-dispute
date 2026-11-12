package com.example.fraudcase.domain;

import java.util.Objects;

/**
 * Trusted reviewer identity resolved by the application — never from client
 * input. The concrete provider is replaceable (synthetic training identity
 * now; authenticated security context later).
 */
public record ReviewerPrincipal(String reviewerId, ReviewerRole role) {

    public ReviewerPrincipal {
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("reviewerId is required");
        }
        Objects.requireNonNull(role, "role is required");
    }

    public boolean mayDisposition() {
        return role == ReviewerRole.REVIEWER;
    }
}
