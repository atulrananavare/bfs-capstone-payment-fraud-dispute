package com.example.fraudcase.memory;

import com.example.fraudcase.domain.CaseMessage;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.persistence.CaseMessageEntity;
import com.example.fraudcase.persistence.CaseMessageRepository;
import com.example.fraudcase.tools.Determinism;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The append path and full-record read path for case-scoped conversation.
 * Framework-neutral — no Spring AI types.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Case isolation:</b> every query is {@code caseId}-scoped; there is
 *       no unscoped read path for conversation data.</li>
 *   <li><b>Deterministic ordering:</b> {@code sequenceNumber} is a
 *       case-local monotonically increasing value. It is computed and
 *       committed under a per-case lock — the transaction commits before
 *       the lock is released, so a concurrent appender always observes the
 *       previous row and can never choose a colliding sequence. A
 *       {@code UNIQUE(case_id, sequence_number)} constraint is the
 *       database backstop.</li>
 *   <li><b>Append-only, independently committed:</b> each message commits
 *       as its own unit (a received message stays recorded even if a later
 *       step fails). Rows are immutable once written.</li>
 * </ul>
 */
@Component
public class CaseConversationStore {

    private final CaseMessageRepository repository;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, Object> caseLocks =
            new ConcurrentHashMap<>();

    public CaseConversationStore(CaseMessageRepository repository,
                                 PlatformTransactionManager txManager,
                                 Clock clock) {
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Append one message to the case's record. Sequence assignment, insert
     * and commit all happen inside the per-case lock.
     */
    public CaseMessage append(String caseId, ConversationRole role, String content,
                              String clientReference, String referencesSummary) {
        synchronized (caseLocks.computeIfAbsent(caseId, k -> new Object())) {
            return txTemplate.execute(status -> {
                long nextSeq = repository
                        .findTopByCaseIdOrderBySequenceNumberDesc(caseId)
                        .map(e -> e.getSequenceNumber() + 1)
                        .orElse(1L);
                String messageId = Determinism.shortId("MSG", 16,
                        caseId, String.valueOf(nextSeq), role.name(), content);
                String contentHash = Determinism.hashHex(
                        caseId, String.valueOf(nextSeq), role.name(), content);
                CaseMessageEntity entity = new CaseMessageEntity(
                        messageId, caseId, nextSeq, role, content, contentHash,
                        clientReference, referencesSummary,
                        OffsetDateTime.now(clock));
                return toDomain(repository.save(entity));
            });
        }
    }

    /** Full persisted record for the case, deterministic order. */
    public List<CaseMessage> history(String caseId) {
        return repository.findByCaseIdOrderBySequenceNumberAsc(caseId).stream()
                .map(CaseConversationStore::toDomain)
                .toList();
    }

    public Optional<CaseMessage> byClientReference(String caseId,
                                                   String clientReference) {
        return repository.findByCaseIdAndClientReference(caseId, clientReference)
                .map(CaseConversationStore::toDomain);
    }

    public Optional<CaseMessage> atSequence(String caseId, long sequenceNumber) {
        return repository.findByCaseIdAndSequenceNumber(caseId, sequenceNumber)
                .map(CaseConversationStore::toDomain);
    }

    public long count(String caseId) {
        return repository.countByCaseId(caseId);
    }

    static CaseMessage toDomain(CaseMessageEntity e) {
        return new CaseMessage(e.getMessageId(), e.getCaseId(),
                e.getSequenceNumber(), e.getRole(), e.getContent(),
                e.getContentHash(), e.getClientReference(),
                e.getReferencesSummary(), e.getCreatedAt());
    }
}
