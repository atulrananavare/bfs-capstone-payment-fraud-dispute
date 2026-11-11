package com.example.fraudcase.tools;

import com.example.fraudcase.domain.EvidenceProvenance;

/**
 * Allow-listed, read-only evidence tool. Implementations never perform
 * financial, account-control or customer-contacting actions and never call
 * external systems — all evidence is deterministic synthetic training data.
 */
public interface EvidenceTool<R extends ToolRequest, S extends ToolResult> {

    String name();

    EvidenceProvenance origin();

    S execute(R request);
}
