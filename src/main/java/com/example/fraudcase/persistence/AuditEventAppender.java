package com.example.fraudcase.persistence;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Deliberately narrow repository: audit events can only be appended and
 * read. No save-and-flush variants beyond insert-only usage, and no update
 * or delete methods exist — history is append-only by design.
 */
public interface AuditEventAppender extends Repository<AuditEventEntity, String> {

    AuditEventEntity save(AuditEventEntity entity);

    Optional<AuditEventEntity> findById(String eventId);

    List<AuditEventEntity> findByCaseIdOrderByRecordedAt(String caseId);

    long countByCaseId(String caseId);
}
