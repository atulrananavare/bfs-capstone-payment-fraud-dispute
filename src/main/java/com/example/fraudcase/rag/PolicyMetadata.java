package com.example.fraudcase.rag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated synthetic-policy metadata. Only {@code SYNTHETIC_TRAINING}
 * classified documents may ever be ingested — real bank policy is
 * permanently out of scope.
 */
public record PolicyMetadata(
        String documentId,
        String title,
        String version,
        LocalDate effectiveDate,
        String owner,
        String classification,
        Status status,
        String supersedes,
        String supersededBy) {

    public static final String REQUIRED_CLASSIFICATION = "SYNTHETIC_TRAINING";

    public enum Status {
        CURRENT,
        SUPERSEDED,
        WITHDRAWN
    }

    /**
     * Build validated metadata from raw front-matter values. Returns the
     * list of violations; empty means valid. Never throws on bad input —
     * malformed policy is a rejection, not a crash.
     */
    public static List<String> violations(Map<String, String> raw) {
        List<String> violations = new ArrayList<>();
        for (String field : List.of("documentId", "title", "version",
                "effectiveDate", "owner", "classification", "status")) {
            if (raw.get(field) == null || raw.get(field).isBlank()) {
                violations.add("missing required metadata: " + field);
            }
        }
        String cls = raw.get("classification");
        if (cls != null && !cls.isBlank() && !REQUIRED_CLASSIFICATION.equals(cls)) {
            violations.add("classification must be " + REQUIRED_CLASSIFICATION);
        }
        String date = raw.get("effectiveDate");
        if (date != null && !date.isBlank()) {
            try {
                LocalDate.parse(date.trim());
            } catch (RuntimeException ex) {
                violations.add("effectiveDate is not ISO-8601: " + date);
            }
        }
        String status = raw.get("status");
        if (status != null && !status.isBlank()) {
            try {
                Status.valueOf(status.trim().toUpperCase());
            } catch (RuntimeException ex) {
                violations.add("status must be CURRENT, SUPERSEDED or WITHDRAWN");
            }
        }
        return violations;
    }

    /** Caller must check {@link #violations(Map)} first. */
    public static PolicyMetadata from(Map<String, String> raw) {
        return new PolicyMetadata(
                raw.get("documentId").trim(),
                raw.get("title").trim(),
                raw.get("version").trim(),
                LocalDate.parse(raw.get("effectiveDate").trim()),
                raw.get("owner").trim(),
                raw.get("classification").trim(),
                Status.valueOf(raw.get("status").trim().toUpperCase()),
                blankToNull(raw.get("supersedes")),
                blankToNull(raw.get("supersededBy")));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public PolicyMetadata {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(version);
        Objects.requireNonNull(effectiveDate);
    }
}
