package com.example.fraudcase.rag;

import java.util.List;
import java.util.Objects;

/**
 * Bounded retrieval request. Carries only hashed/derived descriptors —
 * never raw customer data — plus the investigation goal.
 */
public record PolicyRetrievalRequest(
        String caseId,
        List<String> descriptors,
        String investigationGoal) {

    public PolicyRetrievalRequest {
        Objects.requireNonNull(caseId, "caseId is required");
        descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
        if (investigationGoal == null || investigationGoal.isBlank()) {
            investigationGoal = "determine correct triage routing and required evidence";
        }
    }
}
