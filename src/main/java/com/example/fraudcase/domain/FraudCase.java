package com.example.fraudcase.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Framework-neutral fraud/dispute case aggregate.
 *
 * <p>The case id and analyst id are server-managed; they are never trusted
 * from client input. State may only change through {@link #transitionTo},
 * which enforces the transition rules on {@link CaseState}.
 */
public class FraudCase {

    private final String caseId;
    private final OffsetDateTime reportedAt;
    private final ReportChannel reportChannel;
    private final String analystId;
    private final CustomerFacts customer;
    private final TransactionFacts transaction;
    private final ClaimFacts claim;
    private final OffsetDateTime createdAt;
    private CaseState state;

    public FraudCase(
            String caseId,
            OffsetDateTime reportedAt,
            ReportChannel reportChannel,
            String analystId,
            CustomerFacts customer,
            TransactionFacts transaction,
            ClaimFacts claim,
            OffsetDateTime createdAt) {
        this.caseId = Objects.requireNonNull(caseId, "caseId is required");
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt is required");
        this.reportChannel = Objects.requireNonNull(reportChannel, "reportChannel is required");
        this.analystId = Objects.requireNonNull(analystId, "analystId is required");
        this.customer = Objects.requireNonNull(customer, "customer is required");
        this.transaction = Objects.requireNonNull(transaction, "transaction is required");
        this.claim = Objects.requireNonNull(claim, "claim is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.state = CaseState.NEW;
    }

    public void transitionTo(CaseState target) {
        if (!state.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(state, target);
        }
        this.state = target;
    }

    /**
     * Persistence rehydration only: sets state from a stored row without
     * transition checks. Application flows must always use
     * {@link #transitionTo}.
     */
    public void restorePersistedState(CaseState persisted) {
        this.state = Objects.requireNonNull(persisted);
    }

    public String caseId() {
        return caseId;
    }

    public CaseState state() {
        return state;
    }

    public OffsetDateTime reportedAt() {
        return reportedAt;
    }

    public ReportChannel reportChannel() {
        return reportChannel;
    }

    public String analystId() {
        return analystId;
    }

    public CustomerFacts customer() {
        return customer;
    }

    public TransactionFacts transaction() {
        return transaction;
    }

    public ClaimFacts claim() {
        return claim;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
