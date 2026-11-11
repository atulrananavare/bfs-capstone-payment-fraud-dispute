package com.example.fraudcase.agent.common;

import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationValidatorTest {

    private final RecommendationValidator validator = new RecommendationValidator();

    private static final String CASE_ID = "FC-TEST0001";
    private static final Set<String> CASE_EVIDENCE = Set.of("EVI-A", "EVI-B");
    private static final Set<String> CASE_TRACES = Set.of("TRC-1", "TRC-2");

    private FraudCaseRecommendation valid() {
        return new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test rationale");
    }

    private List<String> validate(FraudCaseRecommendation rec) {
        return validator.validate(rec, CASE_ID, CASE_EVIDENCE, CASE_TRACES,
                RecommendationMode.RULES, RulesFraudCaseAgent.RULE_IDS);
    }

    @Test
    void v10_validRulesRecommendationAccepted() {
        assertThat(validate(valid())).isEmpty();
    }

    @Test
    void v01_humanReviewFalseRejected() {
        // Domain record itself enforces the invariant — construct-check too.
        assertThatThrownBy(() -> new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, false,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v02_confidenceBelowZeroRejected() {
        assertThatThrownBy(() -> new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, -0.1, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v03_confidenceAboveOneRejected() {
        assertThatThrownBy(() -> new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 1.01, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v04_caseIdMismatchRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                "FC-OTHER999", CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test");
        assertThat(validate(rec)).anyMatch(v -> v.contains("caseId"));
    }

    @Test
    void v05_unknownEnumRejectedSafely() {
        // Enum values are statically typed — an unknown enum cannot even be
        // constructed; JSON boundaries reject them (covered in API tests).
        assertThatThrownBy(() -> CaseClassification.valueOf("BOGUS_ENUM"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v06_foreignCaseEvidenceRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of("EVI-A", "EVI-FOREIGN"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test");
        assertThat(validate(rec)).anyMatch(v -> v.contains("evidenceIds"));
    }

    @Test
    void v07_foreignCaseTraceRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1", "TRC-FOREIGN"), "test");
        assertThat(validate(rec)).anyMatch(v -> v.contains("toolTraceIds"));
    }

    @Test
    void v08_unknownRuleIdRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R007", "R999"),
                List.of("TRC-1"), "test");
        assertThat(validate(rec)).anyMatch(v -> v.contains("unknown rule ids"));
    }

    @Test
    void v09_emptyEvidenceAcceptedOnlyForInsufficientEvidence() {
        FraudCaseRecommendation insufficient = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.INSUFFICIENT_EVIDENCE, RiskLevel.MEDIUM,
                RecommendedRoute.MANUAL_REVIEW, 0.30, true,
                List.of(), List.of(), List.of("transaction details unavailable"),
                List.of("R001"), List.of("TRC-1"), "test");
        assertThat(validate(insufficient)).isEmpty();

        FraudCaseRecommendation emptyEvidence = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.SUSPICIOUS_TRANSACTION, RiskLevel.MEDIUM,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.55, true,
                List.of(), List.of(), List.of(), List.of("R007"),
                List.of("TRC-1"), "test");
        assertThat(validate(emptyEvidence)).anyMatch(v -> v.contains("evidence"));
    }
}
