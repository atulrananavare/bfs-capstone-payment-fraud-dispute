package com.example.fraudcase.agent.common;

import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Citation validation against the trusted retrieved set: only citations
 * that resolve to passages actually retrieved for THIS case may pass —
 * fabricated, foreign, stale or tampered citations are rejected.
 */
class PolicyCitationValidationTest {

    private static final String CASE_ID = "FC-TEST0001";
    private final RecommendationValidator validator = new RecommendationValidator();

    private static final PolicyCitation ALLOWED = new PolicyCitation(
            "POL-CNP-017", "3.2", "3.2 Initial fraud routing",
            "POL-CNP-017-3.2-S04-C01", 1.0,
            "Card-Not-Present Dispute Handling Procedure", "2026-10-01",
            "## 3.2 Initial fraud routing\n\nroute the case for fraud investigation");

    private static FraudCaseRecommendation rec(List<PolicyCitation> citations,
                                               RecommendedRoute route,
                                               CaseClassification classification,
                                               String rationale) {
        return new FraudCaseRecommendation(
                CASE_ID, classification, RiskLevel.HIGH, route, 0.8, true,
                List.of("EVI-A"), citations, List.of(), List.of(),
                List.of("TRC-1"), rationale);
    }

    private List<String> check(FraudCaseRecommendation rec,
                               Set<PolicyCitation> allowed,
                               PolicyContextStatus status) {
        return validator.validate(rec, CASE_ID,
                Set.of("EVI-A"), Set.of("TRC-1"),
                RecommendationMode.SPRING_AI,
                Set.of(), allowed, status);
    }

    @Test
    void validRetrievedCitationPasses() {
        var violations = check(rec(List.of(ALLOWED),
                RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION,
                "Policy POL-CNP-017 supports fraud routing."),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED);
        assertThat(violations).isEmpty();
    }

    @Test
    void rag16_fabricatedChunkIdIsRejected() {
        var fake = new PolicyCitation("POL-CNP-017", "3.2",
                "3.2 Initial fraud routing", "POL-CNP-017-3.2-S99-C99", 0.99);
        assertThat(check(rec(List.of(fake), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("not retrieved for this case"));
    }

    @Test
    void rag17_wrongVersionIsRejected() {
        var wrong = new PolicyCitation("POL-CNP-017", "9.9",
                "3.2 Initial fraud routing", ALLOWED.chunkId(), 1.0);
        assertThat(check(rec(List.of(wrong), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("not retrieved for this case"));
    }

    @Test
    void rag18_wrongSectionIsRejected() {
        var wrong = new PolicyCitation("POL-CNP-017", "3.2",
                "1 Purpose", ALLOWED.chunkId(), 1.0);
        assertThat(check(rec(List.of(wrong), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("not retrieved for this case"));
    }

    @Test
    void rag19_wrongDocumentIdIsRejected() {
        var wrong = new PolicyCitation("POL-OTHER", "3.2",
                "3.2 Initial fraud routing", ALLOWED.chunkId(), 1.0);
        assertThat(check(rec(List.of(wrong), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("not retrieved for this case"));
    }

    @Test
    void rag20_scoreMismatchWithRetrievalIsRejected() {
        var wrong = new PolicyCitation("POL-CNP-017", "3.2",
                "3.2 Initial fraud routing", ALLOWED.chunkId(), 0.40);
        assertThat(check(rec(List.of(wrong), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("score"));
    }

    @Test
    void rag24_tamperedPassageTextIsRejected() {
        var tampered = new PolicyCitation("POL-CNP-017", "3.2",
                "3.2 Initial fraud routing", ALLOWED.chunkId(), 1.0,
                ALLOWED.title(), ALLOWED.effectiveDate(),
                "rewritten passage the model invented");
        assertThat(check(rec(List.of(tampered), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("passage does not match"));
    }

    @Test
    void rag26_citationFromAnotherCasesRetrievalIsRejected() {
        var foreign = new PolicyCitation("POL-DUP-009", "2.0",
                "3 Payment-operations routing",
                "POL-DUP-009-2.0-S03-C01", 0.95);
        assertThat(check(rec(List.of(foreign), RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "cites a passage"),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED))
                .anyMatch(v -> v.contains("not retrieved for this case"));
    }

    @Test
    void rag22_proceduralClaimWithoutCitationIsRejected() {
        var violations = check(rec(List.of(),
                RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION,
                "The policy requires fraud investigation routing here."),
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED);
        assertThat(violations)
                .anyMatch(v -> v.contains("policy") || v.contains("procedural"));
    }

    @Test
    void noMatchForcesAbstentionOrManualReview() {
        var confident = check(rec(List.of(),
                RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION,
                "anomalies support fraud"),
                Set.of(), PolicyContextStatus.NO_MATCH);
        assertThat(confident)
                .anyMatch(v -> v.contains("MANUAL_REVIEW") || v.contains("INSUFFICIENT"));

        var safe = check(rec(List.of(),
                RecommendedRoute.MANUAL_REVIEW,
                CaseClassification.INSUFFICIENT_EVIDENCE,
                "insufficient evidence for classification"),
                Set.of(), PolicyContextStatus.NO_MATCH);
        assertThat(safe).isEmpty();
    }

    @Test
    void rag21_staleStatusCitationsAreImpossibleAfterAbstention() {
        var violations = check(rec(List.of(ALLOWED),
                RecommendedRoute.MANUAL_REVIEW,
                CaseClassification.INSUFFICIENT_EVIDENCE,
                "insufficient evidence"),
                Set.of(), PolicyContextStatus.UNAVAILABLE);
        assertThat(violations)
                .anyMatch(v -> v.contains("impossible") || v.contains("not retrieved"));
    }

    @Test
    void rulesModesStillRejectAllCitations() {
        var violations = validator.validate(rec(List.of(ALLOWED),
                RecommendedRoute.FRAUD_INVESTIGATION,
                CaseClassification.UNAUTHORISED_TRANSACTION, "rules cite"),
                CASE_ID, Set.of("EVI-A"), Set.of("TRC-1"),
                RecommendationMode.RULES, RulesFraudCaseAgent.RULE_IDS,
                Set.of(ALLOWED), PolicyContextStatus.RETRIEVED);
        assertThat(violations).anyMatch(v -> v.contains("policyCitations"));
    }
}
