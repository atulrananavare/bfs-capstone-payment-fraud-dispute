package com.example.fraudcase.application;

import com.example.fraudcase.domain.ReviewerPrincipal;

/**
 * Trusted reviewer-identity seam. The implementation — not the request —
 * decides who is performing a review operation and with which role. A
 * production deployment replaces the synthetic training provider with one
 * backed by the authenticated security context; request payloads can never
 * supply reviewer identity.
 */
public interface ReviewerIdentityProvider {

    /** Resolve the current reviewer principal from trusted context. */
    ReviewerPrincipal resolve();
}
