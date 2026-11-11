package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
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
 * Append-only audit event. The entity guard rejects update/delete attempts
 * and the repository exposes no ordinary mutation methods, so history can
 * only grow.
 */
@Entity
@EntityListeners(AuditEventEntity.AppendOnlyGuard.class)
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @Column(name = "event_id", length = 40)
    private String eventId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 24)
    private ActorType actorType;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(String eventId, String caseId, AuditEventType eventType,
                            ActorType actorType, String payloadHash,
                            OffsetDateTime recordedAt) {
        this.eventId = eventId;
        this.caseId = caseId;
        this.eventType = eventType;
        this.actorType = actorType;
        this.payloadHash = payloadHash;
        this.recordedAt = recordedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getCaseId() {
        return caseId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    /**
     * Defence-in-depth: even if a future repository method were added, JPA
     * update/delete lifecycle callbacks refuse to mutate history.
     */
    public static class AppendOnlyGuard {

        @PreUpdate
        void onUpdate(AuditEventEntity entity) {
            throw new IllegalStateException("Audit events are append-only and cannot be updated");
        }

        @PreRemove
        void onRemove(AuditEventEntity entity) {
            throw new IllegalStateException("Audit events are append-only and cannot be deleted");
        }
    }
}
