package com.example.fraudcase.rag;

import com.example.fraudcase.domain.PolicyPassage;

import java.util.List;

/**
 * Result of one retrieval attempt. {@code RETRIEVED} carries the trusted
 * passages; {@code NO_MATCH} means everything was filtered out (abstain);
 * {@code UNAVAILABLE} means the retrieval layer itself failed.
 */
public record PolicyRetrievalOutcome(
        Status status,
        List<PolicyPassage> passages,
        String querySummary,
        int candidatesEvaluated,
        String failureReason) {

    public enum Status {
        RETRIEVED,
        NO_MATCH,
        UNAVAILABLE
    }

    public PolicyRetrievalOutcome {
        passages = passages == null ? List.of() : List.copyOf(passages);
    }

    public static PolicyRetrievalOutcome retrieved(List<PolicyPassage> passages,
                                                   String querySummary,
                                                   int candidates) {
        return new PolicyRetrievalOutcome(Status.RETRIEVED, passages,
                querySummary, candidates, null);
    }

    public static PolicyRetrievalOutcome noMatch(String querySummary,
                                                 int candidates) {
        return new PolicyRetrievalOutcome(Status.NO_MATCH, List.of(),
                querySummary, candidates, null);
    }

    public static PolicyRetrievalOutcome unavailable(String reason) {
        return new PolicyRetrievalOutcome(Status.UNAVAILABLE, List.of(),
                null, 0, reason);
    }
}
