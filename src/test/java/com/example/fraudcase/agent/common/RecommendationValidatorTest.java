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

    // ---------- Phase 3 mode-aware validation ----------

    private List<String> validateAs(FraudCaseRecommendation rec,
                                    RecommendationMode mode, Set<String> known) {
        return validator.validate(rec, CASE_ID, CASE_EVIDENCE, CASE_TRACES, mode, known);
    }

    private FraudCaseRecommendation aiStyle() {
        return new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(), List.of(), List.of(),
                List.of("TRC-1"), "model rationale");
    }

    @Test
    void v11_validSpringAiOutputAcceptedWithoutRuleIds() {
        assertThat(validateAs(aiStyle(), RecommendationMode.SPRING_AI, Set.of())).isEmpty();
    }

    @Test
    void v12_springAiMustNotClaimRuleIds() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R003"),
                List.of("TRC-1"), "claims rules");
        assertThat(validateAs(rec, RecommendationMode.SPRING_AI, Set.of()))
                .anyMatch(v -> v.contains("rule ids"));
    }

    @Test
    void v13_policyCitationsRejectedInEveryMode() {
        var citation = new com.example.fraudcase.domain.PolicyCitation(
                "DOC-1", "v1", "s1", "c1", 0.9);
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(citation), List.of(), List.of(),
                List.of("TRC-1"), "cites policy");
        assertThat(validateAs(rec, RecommendationMode.SPRING_AI, Set.of()))
                .anyMatch(v -> v.contains("policyCitations"));
        assertThat(validateAs(rec, RecommendationMode.RULES,
                RulesFraudCaseAgent.RULE_IDS))
                .anyMatch(v -> v.contains("policyCitations"));
    }

    @Test
    void v14_rulesFallbackAcceptsKnownRuleIds() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.85, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R003"),
                List.of("TRC-1"), "fallback rationale");
        assertThat(validateAs(rec, RecommendationMode.RULES_FALLBACK,
                RulesFraudCaseAgent.RULE_IDS)).isEmpty();
    }

    @Test
    void v15_rulesFallbackStillRejectsUnknownRuleIds() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.85, true,
                List.of("EVI-A"), List.of(), List.of(), List.of("R999"),
                List.of("TRC-1"), "fallback rationale");
        assertThat(validateAs(rec, RecommendationMode.RULES_FALLBACK,
                RulesFraudCaseAgent.RULE_IDS))
                .anyMatch(v -> v.contains("unknown rule ids"));
    }

    @Test
    void v16_rationaleClaimingExecutedActionRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(), List.of(), List.of(),
                List.of("TRC-1"), "I have blocked the card and issued a refund to the customer.");
        assertThat(validateAs(rec, RecommendationMode.SPRING_AI, Set.of()))
                .anyMatch(v -> v.contains("autonomous action"));
    }

    @Test
    void v17_oversizedRationaleRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(), List.of(), List.of(),
                List.of("TRC-1"), "r".repeat(2100));
        assertThat(validateAs(rec, RecommendationMode.SPRING_AI, Set.of()))
                .anyMatch(v -> v.contains("rationale exceeds"));
    }

    @Test
    void v18_blankRationaleRejected() {
        FraudCaseRecommendation rec = new FraudCaseRecommendation(
                CASE_ID, CaseClassification.UNAUTHORISED_TRANSACTION, RiskLevel.HIGH,
                RecommendedRoute.FRAUD_INVESTIGATION, 0.8, true,
                List.of("EVI-A"), List.of(), List.of(), List.of(),
                List.of("TRC-1"), "   ");
        assertThat(validateAs(rec, RecommendationMode.SPRING_AI, Set.of()))
                .anyMatch(v -> v.contains("rationale is required"));
    }
}
