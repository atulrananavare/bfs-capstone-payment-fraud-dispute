package com.example.fraudcase.agent.rules;

import com.example.fraudcase.DomainFixtures;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.domain.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulesFraudCaseAgentTest {

    private final RulesFraudCaseAgent agent = new RulesFraudCaseAgent();

    private FraudCaseContext ctx(FraudCase fraudCase, List<ToolOutcome> outcomes) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(DomainFixtures.evidence(fraudCase.caseId(),
                EvidenceProvenance.CUSTOMER_INTAKE, EvidenceSeverity.INFO, "claim"));
        for (ToolOutcome o : outcomes) {
            if (o.isSuccessful()) {
                evidence.add(DomainFixtures.evidence(fraudCase.caseId(), o.origin(),
                        EvidenceSeverity.INFO, o.toolName() + " evidence"));
            }
        }
        return new FraudCaseContext(fraudCase, evidence, outcomes,
                List.of("TRC-0000000000000001"));
    }

    private ToolOutcome txnOutcome(boolean found, String authOutcome, boolean duplicate) {
        return DomainFixtures.outcome("getTransactionDetails", ToolStatus.SUCCESS, Map.of(
                "found", String.valueOf(found), "transactionStatus", "POSTED",
                "occurredAt", "2026-11-06T08:42:00+05:30", "amount", "48750.00",
                "channel", "CARD_NOT_PRESENT", "authOutcome", authOutcome,
                "maskedMerchant", DomainFixtures.SAMPLE_MERCHANT,
                "duplicateFingerprintMatch", String.valueOf(duplicate)));
    }

    private ToolOutcome deviceOutcome(String deviceKnown, String trustLevel) {
        return DomainFixtures.outcome("getDeviceSignals", ToolStatus.SUCCESS, Map.of(
                "deviceKnown", deviceKnown, "firstSeenAt", "2026-11-06T08:40:00+05:30",
                "trustLevel", trustLevel, "observations", "test"));
    }

    private ToolOutcome locationOutcome(String distanceKm, String anomalyLevel) {
        return DomainFixtures.outcome("compareRecentLocations", ToolStatus.SUCCESS, Map.of(
                "distanceKm", distanceKm, "timeDeltaMinutes", "130",
                "anomalyLevel", anomalyLevel, "observations", "test"));
    }

    private ToolOutcome merchantOutcome(String riskBand, String blocklistMatch) {
        return DomainFixtures.outcome("getMerchantRiskProfile", ToolStatus.SUCCESS, Map.of(
                "riskBand", riskBand, "blocklistMatch", blocklistMatch,
                "category", "DIGITAL_GOODS", "observations", "test"));
    }

    private ToolOutcome priorsOutcome() {
        return DomainFixtures.outcome("getPriorDisputes", ToolStatus.SUCCESS, Map.of(
                "count", "1", "similarCount", "0", "lookbackDays", "90",
                "observations", "test"));
    }

    private List<ToolOutcome> baseOutcomes() {
        return List.of(
                txnOutcome(true, "SUCCESS", false),
                deviceOutcome("true", "TRUSTED"),
                locationOutcome("10", "LOW"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
    }

    // R01 — missing mandatory evidence
    @Test
    void r01_missingTransactionEvidence() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                DomainFixtures.outcome("getTransactionDetails", ToolStatus.NOT_FOUND,
                        Map.of("found", "false")),
                deviceOutcome("true", "TRUSTED"),
                locationOutcome("10", "LOW"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.INSUFFICIENT_EVIDENCE);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.MANUAL_REVIEW);
        assertThat(rec.humanReviewRequired()).isTrue();
        assertThat(rec.ruleIds()).containsExactly("R001");
        assertThat(rec.missingInformation())
                .anyMatch(m -> m.contains("transaction details"));
    }

    // R02 — confirmed duplicate
    @Test
    void r02_confirmedDuplicate() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.DUPLICATE_CHARGE, MerchantRecognition.RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "SUCCESS", true),
                deviceOutcome("true", "TRUSTED"),
                locationOutcome("10", "LOW"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.DUPLICATE_PROCESSING);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.PAYMENT_OPERATIONS);
        assertThat(rec.humanReviewRequired()).isTrue();
        assertThat(rec.ruleIds()).containsExactly("R002");
        assertThat(rec.rationale()).contains("duplicate");
    }

    // R04 — merchant recognised + fulfilment/cancellation/amount
    @Test
    void r04_merchantDispute() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.NON_RECEIPT, MerchantRecognition.RECOGNISED);
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, baseOutcomes()));

        assertThat(rec.classification()).isEqualTo(CaseClassification.MERCHANT_DISPUTE);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.DISPUTE_OPERATIONS);
        assertThat(rec.humanReviewRequired()).isTrue();
        assertThat(rec.ruleIds()).containsExactly("R004");
    }

    // R04 variant: cancellation
    @Test
    void r04_cancellationIssue() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.CANCELLED_SERVICE, MerchantRecognition.RECOGNISED);
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, baseOutcomes()));
        assertThat(rec.classification()).isEqualTo(CaseClassification.MERCHANT_DISPUTE);
        assertThat(rec.ruleIds()).containsExactly("R004");
    }

    // R03 — denies + multiple independent anomalies
    @Test
    void r03_unauthorisedWithIndependentAnomalies() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "UNAVAILABLE", false),
                deviceOutcome("false", "NEW_DEVICE"),
                locationOutcome("1450", "HIGH"),
                merchantOutcome("MEDIUM", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.UNAUTHORISED_TRANSACTION);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.FRAUD_INVESTIGATION);
        assertThat(rec.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(rec.humanReviewRequired()).isTrue();
        assertThat(rec.ruleIds()).containsExactly("R003");
        // Authentication outcome appears as missing information — not invented.
        assertThat(rec.missingInformation())
                .anyMatch(m -> m.contains("authentication outcome"));
    }

    // R03 — anomalies but claim does not establish unauthorised use
    @Test
    void r03_suspiciousWithoutDenial() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.OTHER, MerchantRecognition.RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "FAILED", false),
                deviceOutcome("false", "NEW_DEVICE"),
                locationOutcome("1450", "HIGH"),
                merchantOutcome("MEDIUM", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.SUSPICIOUS_TRANSACTION);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.FRAUD_INVESTIGATION);
        assertThat(rec.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(rec.ruleIds()).containsExactly("R003");
    }

    // R06 — denial contradicted by benign signals
    @Test
    void r06_contradictoryEvidence() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "SUCCESS", false),
                deviceOutcome("true", "TRUSTED"),
                locationOutcome("5", "LOW"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.INSUFFICIENT_EVIDENCE);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.MANUAL_REVIEW);
        assertThat(rec.ruleIds()).containsExactly("R006");
        assertThat(rec.missingInformation())
                .anyMatch(m -> m.contains("contradictory evidence"));
        assertThat(rec.confidence()).isLessThan(0.5);
    }

    // R07 — single anomaly → suspicious, reduced confidence
    @Test
    void r07_singleAnomaly() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "SUCCESS", false),
                deviceOutcome("false", "NEW_DEVICE"),
                locationOutcome("400", "MEDIUM"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.SUSPICIOUS_TRANSACTION);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.FRAUD_INVESTIGATION);
        assertThat(rec.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(rec.ruleIds()).containsExactly("R007");
        assertThat(rec.confidence()).isLessThan(0.7);
    }

    // R08 — no anomalies, no dispute pattern
    @Test
    void r08_genuineOutcome() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.OTHER, MerchantRecognition.RECOGNISED);
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, baseOutcomes()));

        assertThat(rec.classification()).isEqualTo(CaseClassification.GENUINE_TRANSACTION);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.MANUAL_REVIEW);
        assertThat(rec.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(rec.ruleIds()).containsExactly("R008");
    }

    // Precedence: duplicate wins over anomalies
    @Test
    void precedenceDuplicateOverAnomalies() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.DUPLICATE_CHARGE, MerchantRecognition.RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "SUCCESS", true),
                deviceOutcome("false", "NEW_DEVICE"),
                locationOutcome("1450", "HIGH"),
                merchantOutcome("HIGH", "true"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.DUPLICATE_PROCESSING);
        assertThat(rec.ruleIds()).containsExactly("R002");
    }

    // Precedence: mandatory missing wins over everything
    @Test
    void precedenceMissingMandatoryFirst() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.DUPLICATE_CHARGE, MerchantRecognition.RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                DomainFixtures.outcome("getTransactionDetails", ToolStatus.UNAVAILABLE, Map.of()),
                deviceOutcome("false", "NEW_DEVICE"),
                locationOutcome("1450", "HIGH"),
                merchantOutcome("HIGH", "true"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.classification()).isEqualTo(CaseClassification.INSUFFICIENT_EVIDENCE);
        assertThat(rec.recommendedRoute()).isEqualTo(RecommendedRoute.MANUAL_REVIEW);
        assertThat(rec.ruleIds()).containsExactly("R001");
    }

    // R05-style: unavailable tool evidence is reported missing, never invented
    @Test
    void unavailableToolEvidenceIsReportedMissing() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.OTHER, MerchantRecognition.RECOGNISED);
        List<ToolOutcome> outcomes = List.of(
                txnOutcome(true, "SUCCESS", false),
                DomainFixtures.outcome("getDeviceSignals", ToolStatus.UNAVAILABLE, Map.of()),
                locationOutcome("10", "LOW"),
                merchantOutcome("LOW", "false"),
                priorsOutcome());
        FraudCaseRecommendation rec = agent.analyze(ctx(fraudCase, outcomes));

        assertThat(rec.missingInformation())
                .anyMatch(m -> m.contains("getDeviceSignals") && m.contains("UNAVAILABLE"));
    }

    // Confidence is deterministic for identical input
    @Test
    void outputIsDeterministicForIdenticalInput() {
        FraudCase fraudCase = DomainFixtures.fraudCase(
                IssueType.MERCHANT_NOT_RECOGNISED, MerchantRecognition.NOT_RECOGNISED);
        List<ToolOutcome> outcomes = DomainFixtures.sampleCaseOutcomes(
                "UNAVAILABLE", "false", "NEW_DEVICE", "HIGH");
        FraudCaseRecommendation a = agent.analyze(ctx(fraudCase, outcomes));
        FraudCaseRecommendation b = agent.analyze(ctx(fraudCase, outcomes));
        assertThat(a).isEqualTo(b);
    }
}
