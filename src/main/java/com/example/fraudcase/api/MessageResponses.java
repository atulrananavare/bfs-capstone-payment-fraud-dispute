package com.example.fraudcase.api;

import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.PolicyCitation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Safe response contracts for the conversation endpoints. No raw model
 * output, prompt material, provider diagnostics or foreign-case data ever
 * reaches these DTOs.
 */
public final class MessageResponses {

    private MessageResponses() {
    }

    /**
     * Result of {@code POST /api/cases/{caseId}/messages}.
     * {@code messageId} identifies the persisted ASSISTANT turn;
     * {@code userMessageId} the persisted question turn.
     */
    public record CaseMessageResponse(
            String messageId,
            String userMessageId,
            String caseId,
            String answer,
            List<String> evidenceIds,
            List<PolicyCitation> policyCitations,
            List<String> missingInformation,
            boolean humanReviewRequired,
            String answerSource,
            boolean idempotentReplay,
            OffsetDateTime createdAt) {
    }

    /**
     * One entry of {@code GET /api/cases/{caseId}/messages}: a safe view of
     * a persisted turn — user-visible text only.
     */
    public record CaseMessageView(
            String messageId,
            String caseId,
            ConversationRole role,
            String content,
            long sequenceNumber,
            OffsetDateTime createdAt) {
    }
}
