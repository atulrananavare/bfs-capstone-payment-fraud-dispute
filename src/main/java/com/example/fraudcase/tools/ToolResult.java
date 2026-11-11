package com.example.fraudcase.tools;

import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.ToolStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Typed tool result. Business fields are deterministic for identical
 * fixture input; {@code toolResultId} is content-derived and
 * {@code observedAt} is operational metadata.
 */
public interface ToolResult {

    String toolResultId();

    OffsetDateTime observedAt();

    EvidenceProvenance origin();

    ToolStatus status();

    /** Short redacted summary persisted on the tool trace. No sensitive data. */
    String traceSummary();

    /** Structured attributes handed to processors via {@code ToolOutcome}. */
    Map<String, String> attributes();

    /**
     * Material evidence items derivable from this result. Empty for
     * non-SUCCESS statuses — failed tools never fabricate evidence.
     */
    List<EvidenceItem> toEvidence(String caseId);
}
