package com.example.fraudcase.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral record of one tool execution visible to processors:
 * what ran, how it ended and the structured (already-redacted) attributes it
 * returned. Attributes carry string values only; processors read documented
 * keys. An unsuccessful outcome has an empty attribute map — unavailable
 * evidence is never fabricated.
 */
public record ToolOutcome(
        String toolName,
        EvidenceProvenance origin,
        ToolStatus status,
        String toolResultId,
        OffsetDateTime observedAt,
        Map<String, String> attributes,
        String summary) {

    public ToolOutcome {
        Objects.requireNonNull(toolName, "toolName is required");
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(status, "status is required");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        summary = summary == null ? "" : summary;
    }

    public boolean isSuccessful() {
        return status == ToolStatus.SUCCESS;
    }

    public String attribute(String key) {
        return attributes.get(key);
    }
}
