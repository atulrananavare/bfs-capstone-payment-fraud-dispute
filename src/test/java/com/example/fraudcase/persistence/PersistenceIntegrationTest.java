package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ReportChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PersistenceIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private FraudCaseRepository caseRepository;
    @Autowired
    private TransactionClaimRepository claimRepository;
    @Autowired
    private EvidenceItemRepository evidenceRepository;
    @Autowired
    private ToolTraceRepository traceRepository;
    @Autowired
    private RecommendationRepository recommendationRepository;
    @Autowired
    private AuditEventAppender auditAppender;
    @Autowired
    private ReviewDispositionRepository reviewRepository;
    @Autowired
    private PolicyCitationRepository citationRepository;

    private static final OffsetDateTime T =
            OffsetDateTime.parse("2026-11-11T09:15:00Z");

    private FraudCaseEntity newCase(String id) {
        return new FraudCaseEntity(id, "CUST-••••-4821", "IN-KA",
                ReportChannel.WEB_PORTAL, T, CaseState.NEW,
                "unauthenticated-analyst", T);
    }

    private TransactionClaimEntity newClaim(String caseId) {
        return new TransactionClaimEntity(
                FraudCaseMapper.claimIdFor(caseId), caseId, "TXN-2026-784102",
                IssueType.MERCHANT_NOT_RECOGNISED,
                "I do not recognise this purchase and still have my card.",
                CardPossession.IN_POSSESSION, MerchantRecognition.NOT_RECOGNISED,
                new BigDecimal("48750.00"), "INR",
                OffsetDateTime.parse("2026-11-06T08:42:00+05:30"),
                TransactionStatus.POSTED, TransactionChannel.CARD_NOT_PRESENT,
                "NORTHSTAR DIGITAL ••••73");
    }

    private FraudCaseEntity persistCaseWithClaim(String caseId) {
        FraudCaseEntity c = caseRepository.save(newCase(caseId));
        claimRepository.save(newClaim(caseId));
        return c;
    }

    @Test
    void p01_createAndRetrieveFraudCase() {
        persistCaseWithClaim("FC-P01");
        FraudCaseEntity found = caseRepository.findById("FC-P01").orElseThrow();
        assertThat(found.getMaskedCustomerRef()).isEqualTo("CUST-••••-4821");
        assertThat(found.getStatus()).isEqualTo(CaseState.NEW);
        assertThat(found.getReportedAt()).isEqualTo(T);
        assertThat(found.getCreatedBy()).isEqualTo("unauthenticated-analyst");
    }

    @Test
    void p02_claimPreservesOriginalStatement() {
        persistCaseWithClaim("FC-P02");
        TransactionClaimEntity claim = claimRepository.findByCaseId("FC-P02").orElseThrow();
        assertThat(claim.getStatement())
                .isEqualTo("I do not recognise this purchase and still have my card.");
        assertThat(claim.getIssueType()).isEqualTo(IssueType.MERCHANT_NOT_RECOGNISED);
        assertThat(claim.getClaimId()).isEqualTo(FraudCaseMapper.claimIdFor("FC-P02"));
    }

    @Test
    void p03_evidenceItemPersistsWithProvenance() {
        persistCaseWithClaim("FC-P03");
        evidenceRepository.save(new EvidenceItemEntity(
                "EVI-TEST01", "FC-P03", "INV-1", EvidenceType.DEVICE,
                EvidenceSeverity.HIGH, "deviceKnown=false",
                EvidenceProvenance.DEVICE_TOOL, T));
        EvidenceItemEntity found = evidenceRepository.findById("EVI-TEST01").orElseThrow();
        assertThat(found.getProvenance()).isEqualTo(EvidenceProvenance.DEVICE_TOOL);
        assertThat(found.getSeverity()).isEqualTo(EvidenceSeverity.HIGH);
        assertThat(found.getObservedAt()).isEqualTo(T);
    }

    @Test
    void p04_toolTracePersists() {
        persistCaseWithClaim("FC-P04");
        traceRepository.save(new ToolTraceEntity(
                "TRC-T1", "FC-P04", "INV-1", "getDeviceSignals",
                "abc123hash", ToolStatus.SUCCESS, 12L,
                "status=SUCCESS deviceKnown=false", T));
        List<ToolTraceEntity> traces = traceRepository.findByCaseIdOrderByExecutedAt("FC-P04");
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getRequestHash()).isEqualTo("abc123hash");
        assertThat(traces.get(0).getResultSummary()).doesNotContain("4111");
    }

    @Test
    void p05_recommendationPersistsWithCollections() {
        persistCaseWithClaim("FC-P05");
        evidenceRepository.save(new EvidenceItemEntity(
                "EVI-R1", "FC-P05", "INV-1", EvidenceType.DEVICE,
                EvidenceSeverity.HIGH, "s", EvidenceProvenance.DEVICE_TOOL, T));
        RecommendationEntity rec = new RecommendationEntity(
                "REC-R1", "FC-P05", "INV-1", RecommendationMode.RULES,
                CaseClassification.UNAUTHORISED_TRANSACTION,
                RecommendedRoute.FRAUD_INVESTIGATION, RiskLevel.HIGH,
                0.85, true, "rationale", T);
        rec.setEvidenceIds(List.of("EVI-R1"));
        rec.setRuleIds(List.of("R003"));
        rec.setToolTraceIds(List.of());
        rec.setMissingInformation(List.of("transaction authentication outcome unavailable"));
        recommendationRepository.save(rec);

        RecommendationEntity found = recommendationRepository
                .findTopByCaseIdOrderByCreatedAtDesc("FC-P05").orElseThrow();
        assertThat(found.getClassification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
        assertThat(found.isHumanReviewRequired()).isTrue();
        assertThat(found.getEvidenceIds()).containsExactly("EVI-R1");
        assertThat(found.getRuleIds()).containsExactly("R003");
        assertThat(found.getMissingInformation()).contains("transaction authentication outcome unavailable");
    }

    @Test
    void p06_auditEventPersists() {
        persistCaseWithClaim("FC-P06");
        auditAppender.save(new AuditEventEntity(
                "EVT-A1", "FC-P06", AuditEventType.CASE_CREATED,
                ActorType.ANALYST, "hash123", T));
        List<AuditEventEntity> events = auditAppender.findByCaseIdOrderByRecordedAt("FC-P06");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(AuditEventType.CASE_CREATED);
        assertThat(events.get(0).getPayloadHash()).isEqualTo("hash123");
    }

    @Test
    void p07_relationshipsLinkToCorrectCase() {
        persistCaseWithClaim("FC-P07");
        persistCaseWithClaim("FC-P07B");
        evidenceRepository.save(new EvidenceItemEntity(
                "EVI-LINK1", "FC-P07", "INV-1", EvidenceType.DEVICE,
                EvidenceSeverity.HIGH, "s", EvidenceProvenance.DEVICE_TOOL, T));
        evidenceRepository.save(new EvidenceItemEntity(
                "EVI-LINK2", "FC-P07B", "INV-1", EvidenceType.DEVICE,
                EvidenceSeverity.LOW, "s", EvidenceProvenance.DEVICE_TOOL, T));

        assertThat(claimRepository.findByCaseId("FC-P07").orElseThrow().getCaseId())
                .isEqualTo("FC-P07");
        assertThat(evidenceRepository.findByCaseIdOrderByObservedAt("FC-P07"))
                .extracting(EvidenceItemEntity::getEvidenceId)
                .containsExactly("EVI-LINK1");
    }

    @Test
    void p08_transactionRollbackRestoresCleanState() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            caseRepository.save(newCase("FC-P08"));
            claimRepository.save(newClaim("FC-P08"));
            status.setRollbackOnly();
            return null;
        });
        assertThat(caseRepository.findById("FC-P08")).isEmpty();
        assertThat(claimRepository.findByCaseId("FC-P08")).isEmpty();
    }

    @Test
    void p09_schemaInitialisedByMigrations() {
        Set<String> tables = Set.copyOf(jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA='PUBLIC'", String.class));
        assertThat(tables).contains(
                "FRAUD_CASE", "TRANSACTION_CLAIM", "EVIDENCE_ITEM", "TOOL_TRACE",
                "RECOMMENDATION", "POLICY_CITATION", "REVIEW_DISPOSITION",
                "AUDIT_EVENT", "RECOMMENDATION_EVIDENCE", "RECOMMENDATION_RULE_IDS",
                "RECOMMENDATION_TRACE_IDS", "RECOMMENDATION_MISSING_INFO");
    }

    @Test
    void p10_auditTrailIsAppendOnlyByDesign() {
        // The repository exposes no update/delete methods at all.
        for (Method m : AuditEventAppender.class.getDeclaredMethods()) {
            assertThat(m.getName().toLowerCase())
                    .doesNotContain("delete")
                    .doesNotContain("update");
        }
        // The entity is immutable (no setters) and guarded by @PreUpdate/@PreRemove.
        for (Method m : AuditEventEntity.class.getDeclaredMethods()) {
            assertThat(m.getName()).doesNotStartWith("set");
        }
        persistCaseWithClaim("FC-P10");
        auditAppender.save(new AuditEventEntity(
                "EVT-P10", "FC-P10", AuditEventType.CASE_CREATED,
                ActorType.SYSTEM, "hash", T));
        assertThat(auditAppender.countByCaseId("FC-P10")).isEqualTo(1);
    }

    @Test
    void p11_noSensitivePaymentColumnsExist() {
        List<String> columns = jdbc.queryForList(
                "SELECT TABLE_NAME || '.' || COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA='PUBLIC'", String.class);
        assertThat(columns)
                .noneMatch(c -> c.matches("(?i).*(CARD_NUMBER|PAN|CVV|CVC|ACCOUNT_NUMBER"
                        + "|ACCOUNT_NO|PASSWORD|API_KEY|SECRET|TOKEN).*"));
    }
}
