package com.example.fraudcase.application;

import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.tools.Determinism;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The only write path for the audit trail: appends a new event. Payloads are
 * hashed summaries — never raw case data or sensitive values.
 */
@Service
public class AuditService {

    private final AuditEventAppender appender;
    private final Clock clock;

    public AuditService(AuditEventAppender appender, Clock clock) {
        this.appender = appender;
        this.clock = clock;
    }

    /**
     * Append an audit event. {@code payloadSummary} must be a safe canonical
     * string (identifiers, enums, hashes) — never raw payloads.
     */
    public AuditEventEntity record(String caseId, AuditEventType eventType,
                                   ActorType actorType, String payloadSummary) {
        String payloadHash = Determinism.hashHex(eventType.name(), caseId, payloadSummary);
        String eventId = "EVT-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        return appender.save(new AuditEventEntity(
                eventId, caseId, eventType, actorType, payloadHash,
                OffsetDateTime.now(clock)));
    }
}
