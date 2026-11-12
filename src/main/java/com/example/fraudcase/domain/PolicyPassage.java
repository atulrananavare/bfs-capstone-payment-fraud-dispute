package com.example.fraudcase.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One retrieved synthetic-policy passage with its full source metadata.
 * Framework-neutral: produced by the retrieval layer, consumed by
 * processors and validation. The passage text is the exact ingested chunk
 * — never model-generated — and is still untrusted prompt data.
 */
public record PolicyPassage(
        String documentId,
        String title,
        String version,
        LocalDate effectiveDate,
        String owner,
        String section,
        String chunkId,
        double score,
        String passage) {

    public PolicyPassage {
        Objects.requireNonNull(documentId, "documentId is required");
        Objects.requireNonNull(version, "version is required");
        Objects.requireNonNull(section, "section is required");
        Objects.requireNonNull(chunkId, "chunkId is required");
        Objects.requireNonNull(passage, "passage is required");
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1 inclusive");
        }
    }
}
