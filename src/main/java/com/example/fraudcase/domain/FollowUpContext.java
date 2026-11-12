package com.example.fraudcase.domain;

import java.util.List;
import java.util.Objects;

/**
 * Framework-neutral input for a case-scoped conversational follow-up.
 *
 * <p>Everything in here belongs to exactly one case: validated intake,
 * persisted evidence, the latest validated recommendation (with its
 * citations), freshly retrieved policy passages and the bounded
 * conversation window. The follow-up text itself is untrusted data.
 */
public record FollowUpContext(
        FraudCase fraudCase,
        List<EvidenceItem> evidence,
        FraudCaseRecommendation latestRecommendation,
        List<PolicyPassage> policyPassages,
        PolicyContextStatus policyStatus,
        List<ConversationTurn> history,
        String followUpText,
        String correlationId) {

    public FollowUpContext {
        Objects.requireNonNull(fraudCase, "fraudCase is required");
        Objects.requireNonNull(followUpText, "followUpText is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        policyPassages = policyPassages == null
                ? List.of() : List.copyOf(policyPassages);
        history = history == null ? List.of() : List.copyOf(history);
        policyStatus = policyStatus == null
                ? PolicyContextStatus.NOT_REQUESTED : policyStatus;
    }

    public String caseId() {
        return fraudCase.caseId();
    }
}
