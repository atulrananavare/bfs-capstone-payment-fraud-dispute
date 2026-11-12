package com.example.fraudcase.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One persisted conversation turn belonging to exactly one case.
 * Framework-neutral; ordering is {@code sequenceNumber} — a case-local
 * monotonically increasing value, not a timestamp.
 */
public record CaseMessage(
        String messageId,
        String caseId,
        long sequenceNumber,
        ConversationRole role,
        String content,
        String contentHash,
        String clientReference,
        String referencesSummary,
        OffsetDateTime createdAt) {

    public CaseMessage {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(caseId, "caseId is required");
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(content, "content is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException(
                    "sequenceNumber must be a positive case-local value");
        }
    }
}
