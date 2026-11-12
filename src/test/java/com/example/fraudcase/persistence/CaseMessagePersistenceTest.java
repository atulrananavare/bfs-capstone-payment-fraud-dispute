package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.ReportChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence-level guarantees for the case-scoped conversation record:
 * case isolation at the data layer, deterministic sequence ordering,
 * uniqueness backstop and immutable rows.
 */
@DataJpaTest
class CaseMessagePersistenceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private CaseMessageRepository repository;

    private FraudCaseEntity persistCase(String caseId) {
        FraudCaseEntity e = new FraudCaseEntity(caseId, "CUST-••••-4821", "IN-KA",
                ReportChannel.WEB_PORTAL,
                OffsetDateTime.parse("2026-11-11T09:15:00Z"),
                CaseState.NEW, "analyst-synthetic",
                OffsetDateTime.parse("2026-11-11T09:16:00Z"));
        em.persist(e);
        return e;
    }

    private CaseMessageEntity msg(String caseId, long seq,
                                  ConversationRole role, String content) {
        return new CaseMessageEntity(
                "MSG-T" + caseId.substring(2) + seq + role.name().charAt(0),
                caseId, seq, role, content,
                "hash-" + caseId + "-" + seq, null, null,
                OffsetDateTime.parse("2026-11-11T10:00:00Z"));
    }

    @Test
    void caseScopedQueryNeverReturnsForeignMessages() {
        persistCase("FC-M01A");
        persistCase("FC-M01B");
        em.persist(msg("FC-M01A", 1, ConversationRole.USER, "case A secret fact"));
        em.persist(msg("FC-M01B", 1, ConversationRole.USER, "case B fact"));
        em.flush();

        var a = repository.findByCaseIdOrderBySequenceNumberAsc("FC-M01A");
        var b = repository.findByCaseIdOrderBySequenceNumberAsc("FC-M01B");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).getContent()).isEqualTo("case A secret fact");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).getContent()).isEqualTo("case B fact");
        // The data-layer query enforces scope — no application filtering.
        assertThat(repository.findDistinctCaseIds())
                .containsExactlyInAnyOrder("FC-M01A", "FC-M01B");
    }

    @Test
    void orderingIsBySequenceNotTimestamp() {
        persistCase("FC-M02");
        // Insert out of created_at order; sequence must drive ordering.
        em.persist(new CaseMessageEntity("MSG-2", "FC-M02", 2,
                ConversationRole.ASSISTANT, "second",
                "h2", null, null,
                OffsetDateTime.parse("2026-11-11T09:00:00Z")));
        em.persist(new CaseMessageEntity("MSG-1", "FC-M02", 1,
                ConversationRole.USER, "first",
                "h1", null, null,
                OffsetDateTime.parse("2026-11-11T10:00:00Z")));
        em.flush();

        var rows = repository.findByCaseIdOrderBySequenceNumberAsc("FC-M02");
        assertThat(rows).extracting(CaseMessageEntity::getSequenceNumber)
                .containsExactly(1L, 2L);
        assertThat(rows).extracting(CaseMessageEntity::getContent)
                .containsExactly("first", "second");
    }

    @Test
    void duplicateSequencePerCaseIsRejected() {
        persistCase("FC-M03");
        em.persist(msg("FC-M03", 1, ConversationRole.USER, "one"));
        em.flush();
        assertThatThrownBy(() -> {
            em.persist(msg("FC-M03", 1, ConversationRole.USER, "dup"));
            em.flush();
        }).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    @Test
    void deleteIsRefusedByEntityGuard() {
        persistCase("FC-M05");
        em.persist(msg("FC-M05", 1, ConversationRole.USER, "immutable"));
        em.flush();
        CaseMessageEntity managed = repository
                .findByCaseIdOrderBySequenceNumberAsc("FC-M05").get(0);
        assertThatThrownBy(() -> {
            em.remove(managed);
            em.flush();
        }).hasStackTraceContaining("immutable");
        em.clear();
    }

    @Test
    void clientReferenceLookupIsCaseScoped() {
        persistCase("FC-M06A");
        persistCase("FC-M06B");
        em.persist(new CaseMessageEntity("MSG-A", "FC-M06A", 1,
                ConversationRole.USER, "req", "ha", "req-1", null,
                OffsetDateTime.parse("2026-11-11T10:00:00Z")));
        em.persist(new CaseMessageEntity("MSG-B", "FC-M06B", 1,
                ConversationRole.USER, "req", "hb", "req-1", null,
                OffsetDateTime.parse("2026-11-11T10:00:00Z")));
        em.flush();

        assertThat(repository.findByCaseIdAndClientReference("FC-M06A", "req-1"))
                .map(CaseMessageEntity::getMessageId).contains("MSG-A");
        assertThat(repository.findByCaseIdAndClientReference("FC-M06B", "req-1"))
                .map(CaseMessageEntity::getMessageId).contains("MSG-B");
        // Different case + same reference resolves independently.
        assertThat(repository.findByCaseIdAndClientReference("FC-M06A", "req-9"))
                .isEmpty();
    }
}
