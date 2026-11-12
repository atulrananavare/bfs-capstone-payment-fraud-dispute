package com.example.fraudcase.application;

import com.example.fraudcase.api.FraudCaseIntakeRequest;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.ClaimFacts;
import com.example.fraudcase.domain.CustomerFacts;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.TransactionFacts;
import com.example.fraudcase.observability.CaseMetrics;
import com.example.fraudcase.persistence.FraudCaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Application service for case intake and retrieval.
 *
 * <p>The service owns server-managed fields: {@code caseId} is generated
 * here and {@code analystId} is a documented synthetic training identity
 * until authenticated context is introduced — neither is trusted from the
 * request.
 */
@Service
public class FraudCaseService {

    /**
     * Synthetic training identity placeholder until analyst identity comes
     * from authenticated application context.
     */
    static final String UNAUTHENTICATED_ANALYST = "unauthenticated-analyst";

    private static final int CASE_ID_SUFFIX_LENGTH = 8;

    private static final Logger log = LoggerFactory.getLogger(FraudCaseService.class);

    private final FraudCaseStore store;
    private final AuditService auditService;
    private final Clock clock;
    private final CaseMetrics metrics;

    public FraudCaseService(FraudCaseStore store, AuditService auditService,
                            Clock clock, CaseMetrics metrics) {
        this.store = store;
        this.auditService = auditService;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public FraudCase createCase(FraudCaseIntakeRequest request) {
        FraudCase fraudCase = new FraudCase(
                generateCaseId(),
                request.reportedAt(),
                request.channel(),
                UNAUTHENTICATED_ANALYST,
                new CustomerFacts(
                        request.customer().maskedCustomerRef(),
                        request.customer().homeRegion()),
                new TransactionFacts(
                        request.transaction().transactionRef(),
                        request.transaction().amount(),
                        request.transaction().currency(),
                        request.transaction().occurredAt(),
                        request.transaction().status(),
                        request.transaction().channel(),
                        request.transaction().maskedMerchant()),
                new ClaimFacts(
                        request.claim().issueType(),
                        request.claim().customerStatement(),
                        request.claim().cardPossession(),
                        request.claim().merchantRecognised()),
                OffsetDateTime.now(clock));
        FraudCase saved = store.save(fraudCase);
        auditService.record(saved.caseId(), AuditEventType.CASE_CREATED,
                ActorType.ANALYST,
                "channel=" + saved.reportChannel() + "|status=" + saved.state());
        metrics.caseCreated();
        log.info("CASE_CREATED caseId={} channel={}", saved.caseId(), saved.reportChannel());
        return saved;
    }

    public FraudCase getCase(String caseId) {
        return store.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    /** Read-only analyst worklist, newest first. */
    public List<FraudCase> listCases() {
        return store.findAllRecent();
    }

    private String generateCaseId() {
        return "FC-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, CASE_ID_SUFFIX_LENGTH)
                .toUpperCase();
    }
}
