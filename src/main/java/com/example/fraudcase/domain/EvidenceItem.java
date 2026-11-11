package com.example.fraudcase.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A material, traceable evidence record for a case. {@code evidenceId} is a
 * deterministic content-derived identifier, so re-running the same
 * investigation over identical facts is idempotent and never duplicates
 * evidence.
 */
public record EvidenceItem(
        String evidenceId,
        String caseId,
        EvidenceType type,
        EvidenceSeverity severity,
        String valueSummary,
        EvidenceProvenance provenance,
        OffsetDateTime observedAt) {

    public EvidenceItem {
        Objects.requireNonNull(evidenceId, "evidenceId is required");
        Objects.requireNonNull(caseId, "caseId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(valueSummary, "valueSummary is required");
        Objects.requireNonNull(provenance, "provenance is required");
        Objects.requireNonNull(observedAt, "observedAt is required");
    }
}
