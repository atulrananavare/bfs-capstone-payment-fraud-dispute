package com.example.fraudcase.domain;

import java.util.Objects;

/**
 * Reference to a retrieved synthetic-policy passage supporting a
 * recommendation. Citations are always exact metadata from retrieval;
 * policy is never fabricated.
 */
public record PolicyCitation(
        String documentId,
        String version,
        String section,
        String chunkId,
        double score) {

    public PolicyCitation {
        Objects.requireNonNull(documentId, "documentId is required");
        Objects.requireNonNull(version, "version is required");
        Objects.requireNonNull(section, "section is required");
        Objects.requireNonNull(chunkId, "chunkId is required");
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1 inclusive");
        }
    }
}
