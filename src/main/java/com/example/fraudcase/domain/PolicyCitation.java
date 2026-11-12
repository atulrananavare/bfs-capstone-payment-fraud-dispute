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
        double score,
        String title,
        String effectiveDate,
        String passage) {

    public PolicyCitation {
        Objects.requireNonNull(documentId, "documentId is required");
        Objects.requireNonNull(version, "version is required");
        Objects.requireNonNull(section, "section is required");
        Objects.requireNonNull(chunkId, "chunkId is required");
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1 inclusive");
        }
    }

    /** Convenience constructor for citations without display metadata. */
    public PolicyCitation(String documentId, String version, String section,
                          String chunkId, double score) {
        this(documentId, version, section, chunkId, score, null, null, null);
    }

    /**
     * Build a citation from a trusted retrieved passage. This is the only
     * supported way to produce a citation: the model may point at a chunk
     * id, but every field here comes from the retrieval layer.
     */
    public static PolicyCitation from(PolicyPassage passage) {
        return new PolicyCitation(
                passage.documentId(), passage.version(), passage.section(),
                passage.chunkId(), passage.score(), passage.title(),
                passage.effectiveDate() == null
                        ? null : passage.effectiveDate().toString(),
                passage.passage());
    }
}
