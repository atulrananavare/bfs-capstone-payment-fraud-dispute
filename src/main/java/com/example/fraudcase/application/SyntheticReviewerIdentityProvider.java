package com.example.fraudcase.application;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ReviewerPrincipal;
import org.springframework.stereotype.Component;

/**
 * Documented synthetic training identity (capstone seam). The reviewer id
 * and role come from application configuration — never from the request —
 * so a client cannot impersonate a reviewer by changing JSON. Replaced by
 * an authenticated-context implementation when real security is added.
 */
@Component
public class SyntheticReviewerIdentityProvider implements ReviewerIdentityProvider {

    private final CapstoneProperties properties;

    public SyntheticReviewerIdentityProvider(CapstoneProperties properties) {
        this.properties = properties;
    }

    @Override
    public ReviewerPrincipal resolve() {
        return new ReviewerPrincipal(
                properties.getReview().getActorId(),
                properties.getReview().getActorRole());
    }
}
