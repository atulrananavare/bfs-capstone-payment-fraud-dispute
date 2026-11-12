package com.example.fraudcase.memory;

import com.example.fraudcase.config.AppConfig;
import com.example.fraudcase.domain.CaseMessage;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.ReportChannel;
import com.example.fraudcase.persistence.FraudCaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store-level guarantees: deterministic sequence assignment, concurrency
 * safety, case isolation and idempotent client references.
 */
@DataJpaTest
@Import({CaseConversationStore.class, AppConfig.class})
class CaseConversationStoreTest {

    @Autowired
    private CaseConversationStore store;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * Insert the parent case in a {@code REQUIRES_NEW} transaction so the
     * row commits immediately and is visible to worker threads running
     * their own transactions (the surrounding {@code @DataJpaTest}
     * transaction never commits).
     */
    private void persistCaseCommitted(String caseId) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition
                        .PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status ->
                new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                        "INSERT INTO fraud_case (case_id, masked_customer_ref,"
                                + " home_region, report_channel, reported_at,"
                                + " status, created_by, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        caseId, "CUST-••••-4821", "IN-KA", "WEB_PORTAL",
                        java.sql.Timestamp.from(OffsetDateTime.parse(
                                "2026-11-11T09:15:00Z").toInstant()),
                        "NEW", "analyst-synthetic",
                        java.sql.Timestamp.from(OffsetDateTime.parse(
                                "2026-11-11T09:16:00Z").toInstant())));
    }

    private void persistCase(String caseId) {
        em.persist(new FraudCaseEntity(caseId, "CUST-••••-4821", "IN-KA",
                ReportChannel.WEB_PORTAL,
                OffsetDateTime.parse("2026-11-11T09:15:00Z"),
                CaseState.NEW, "analyst-synthetic",
                OffsetDateTime.parse("2026-11-11T09:16:00Z")));
        em.flush();
    }

    @Test
    void sequenceIsCaseLocalAndMonotonic() {
        persistCase("FC-S01A");
        persistCase("FC-S01B");

        CaseMessage a1 = store.append("FC-S01A", ConversationRole.USER, "a1", null, null);
        CaseMessage a2 = store.append("FC-S01A", ConversationRole.ASSISTANT, "a2", null, null);
        CaseMessage b1 = store.append("FC-S01B", ConversationRole.USER, "b1", null, null);
        CaseMessage a3 = store.append("FC-S01A", ConversationRole.USER, "a3", null, null);

        assertThat(a1.sequenceNumber()).isEqualTo(1);
        assertThat(a2.sequenceNumber()).isEqualTo(2);
        assertThat(a3.sequenceNumber()).isEqualTo(3);
        // Case B has its own sequence — case-local, not global.
        assertThat(b1.sequenceNumber()).isEqualTo(1);

        assertThat(store.history("FC-S01A"))
                .extracting(CaseMessage::content)
                .containsExactly("a1", "a2", "a3");
        assertThat(store.history("FC-S01B"))
                .extracting(CaseMessage::content)
                .containsExactly("b1");
    }

    @Test
    void concurrentAppendsProduceDistinctOrderedSequences() throws Exception {
        persistCaseCommitted("FC-S02");
        int threads = 4;
        var pool = Executors.newFixedThreadPool(threads);
        var ready = new CountDownLatch(threads);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    ready.await(5, TimeUnit.SECONDS);
                    results.add(store.append("FC-S02", ConversationRole.USER,
                            "msg-" + n, null, null).sequenceNumber());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        assertThat(failures).isEmpty();
        assertThat(results).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(store.history("FC-S02"))
                .extracting(CaseMessage::sequenceNumber)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void lookupHelpersAreCaseScoped() {
        persistCase("FC-S03A");
        persistCase("FC-S03B");
        store.append("FC-S03A", ConversationRole.USER, "q", "req-9", null);
        store.append("FC-S03A", ConversationRole.ASSISTANT, "a", null, null);

        assertThat(store.byClientReference("FC-S03A", "req-9"))
                .map(CaseMessage::content).contains("q");
        assertThat(store.byClientReference("FC-S03B", "req-9")).isEmpty();
        assertThat(store.atSequence("FC-S03A", 2))
                .map(CaseMessage::role).contains(ConversationRole.ASSISTANT);
        assertThat(store.count("FC-S03A")).isEqualTo(2);
        assertThat(store.count("FC-S03B")).isZero();
    }
}
