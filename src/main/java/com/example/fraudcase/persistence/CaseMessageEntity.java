package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.ConversationRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One persisted conversation message, scoped to exactly one case. Messages
 * are immutable once written: correction semantics are append-only (a later
 * message corrects an earlier one; history is never rewritten).
 */
@Entity
@EntityListeners(CaseMessageEntity.ImmutableGuard.class)
@Table(name = "case_message")
public class CaseMessageEntity {

    @Id
    @Column(name = "message_id", length = 40)
    private String messageId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private ConversationRole role;

    @Column(name = "content", nullable = false, length = 4000)
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "client_reference", length = 128)
    private String clientReference;

    @Column(name = "references_summary", length = 2000)
    private String referencesSummary;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected CaseMessageEntity() {
    }

    public CaseMessageEntity(String messageId, String caseId, long sequenceNumber,
                             ConversationRole role, String content,
                             String contentHash, String clientReference,
                             String referencesSummary, OffsetDateTime createdAt) {
        this.messageId = messageId;
        this.caseId = caseId;
        this.sequenceNumber = sequenceNumber;
        this.role = role;
        this.content = content;
        this.contentHash = contentHash;
        this.clientReference = clientReference;
        this.referencesSummary = referencesSummary;
        this.createdAt = createdAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getCaseId() {
        return caseId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public ConversationRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getClientReference() {
        return clientReference;
    }

    public String getReferencesSummary() {
        return referencesSummary;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Defence-in-depth: conversation history may not be rewritten or
     * deleted through normal flows.
     */
    public static class ImmutableGuard {

        @PreUpdate
        void onUpdate(CaseMessageEntity entity) {
            throw new IllegalStateException(
                    "Case messages are immutable and cannot be updated");
        }

        @PreRemove
        void onRemove(CaseMessageEntity entity) {
            throw new IllegalStateException(
                    "Case messages are immutable and cannot be deleted");
        }
    }
}
