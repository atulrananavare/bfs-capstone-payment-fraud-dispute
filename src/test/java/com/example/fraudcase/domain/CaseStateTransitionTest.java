package com.example.fraudcase.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseStateTransitionTest {

    @Test
    void allSpecAllowedTransitionsArePermitted() {
        assertThat(CaseState.NEW.canTransitionTo(CaseState.ENRICHING)).isTrue();
        assertThat(CaseState.ENRICHING.canTransitionTo(CaseState.READY_FOR_REVIEW)).isTrue();
        assertThat(CaseState.ENRICHING.canTransitionTo(CaseState.INCOMPLETE)).isTrue();
        assertThat(CaseState.INCOMPLETE.canTransitionTo(CaseState.ENRICHING)).isTrue();
        assertThat(CaseState.INCOMPLETE.canTransitionTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.READY_FOR_REVIEW.canTransitionTo(CaseState.UNDER_REVIEW)).isTrue();
        assertThat(CaseState.UNDER_REVIEW.canTransitionTo(CaseState.ROUTED)).isTrue();
        assertThat(CaseState.UNDER_REVIEW.canTransitionTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.ROUTED.canTransitionTo(CaseState.CLOSED)).isTrue();
    }

    @Test
    void closedIsTerminal() {
        assertThat(CaseState.CLOSED.isTerminal()).isTrue();
        assertThat(CaseState.CLOSED.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(CaseState.class)
    void noStateCanTransitionToNew(CaseState from) {
        assertThat(from.canTransitionTo(CaseState.NEW)).isFalse();
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(CaseState.NEW.canTransitionTo(CaseState.CLOSED)).isFalse();
        assertThat(CaseState.NEW.canTransitionTo(CaseState.ROUTED)).isFalse();
        assertThat(CaseState.ENRICHING.canTransitionTo(CaseState.UNDER_REVIEW)).isFalse();
        assertThat(CaseState.ROUTED.canTransitionTo(CaseState.ENRICHING)).isFalse();
        assertThat(CaseState.CLOSED.canTransitionTo(CaseState.ENRICHING)).isFalse();
    }

    @Test
    void fraudCaseRejectsIllegalTransition() {
        FraudCase fraudCase = buildCase();
        assertThatThrownBy(() -> fraudCase.transitionTo(CaseState.READY_FOR_REVIEW))
                .isInstanceOf(InvalidStateTransitionException.class);

        fraudCase.transitionTo(CaseState.ENRICHING);
        assertThat(fraudCase.state()).isEqualTo(CaseState.ENRICHING);
        assertThatThrownBy(() -> fraudCase.transitionTo(CaseState.ROUTED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    private static FraudCase buildCase() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        return new FraudCase(
                "FC-TEST0001",
                now,
                ReportChannel.WEB_PORTAL,
                "analyst-1",
                new CustomerFacts("CUST-••••-4821", "IN-KA"),
                new TransactionFacts(
                        "TXN-2026-784102",
                        new java.math.BigDecimal("10.00"),
                        "INR",
                        now,
                        TransactionStatus.POSTED,
                        TransactionChannel.CARD_NOT_PRESENT,
                        "NORTHSTAR DIGITAL ••••73"),
                new ClaimFacts(
                        IssueType.MERCHANT_NOT_RECOGNISED,
                        "synthetic statement",
                        CardPossession.IN_POSSESSION,
                        MerchantRecognition.NOT_RECOGNISED),
                now);
    }
}
