package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.ClaimFacts;
import com.example.fraudcase.domain.CustomerFacts;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.TransactionFacts;
import com.example.fraudcase.tools.Determinism;

/**
 * Maps between the framework-neutral domain aggregate and JPA entities.
 */
public final class FraudCaseMapper {

    private FraudCaseMapper() {
    }

    /** Deterministic claim id derived from the case id. */
    public static String claimIdFor(String caseId) {
        return Determinism.shortId("CLM", 12, "claim", caseId);
    }

    public static FraudCaseEntity toCaseEntity(FraudCase fraudCase) {
        return new FraudCaseEntity(
                fraudCase.caseId(),
                fraudCase.customer().maskedCustomerRef(),
                fraudCase.customer().homeRegion(),
                fraudCase.reportChannel(),
                fraudCase.reportedAt(),
                fraudCase.state(),
                fraudCase.analystId(),
                fraudCase.createdAt());
    }

    public static TransactionClaimEntity toClaimEntity(FraudCase fraudCase) {
        ClaimFacts claim = fraudCase.claim();
        TransactionFacts tx = fraudCase.transaction();
        return new TransactionClaimEntity(
                claimIdFor(fraudCase.caseId()),
                fraudCase.caseId(),
                tx.transactionRef(),
                claim.issueType(),
                claim.customerStatement(),
                claim.cardPossession(),
                claim.merchantRecognised(),
                tx.amount(),
                tx.currency(),
                tx.occurredAt(),
                tx.status(),
                tx.channel(),
                tx.maskedMerchant());
    }

    public static FraudCase toDomain(FraudCaseEntity caseEntity,
                                     TransactionClaimEntity claimEntity) {
        FraudCase fraudCase = new FraudCase(
                caseEntity.getCaseId(),
                caseEntity.getReportedAt(),
                caseEntity.getReportChannel(),
                caseEntity.getCreatedBy(),
                new CustomerFacts(caseEntity.getMaskedCustomerRef(), caseEntity.getHomeRegion()),
                new TransactionFacts(
                        claimEntity.getTransactionRef(),
                        claimEntity.getAmount(),
                        claimEntity.getCurrency(),
                        claimEntity.getOccurredAt(),
                        claimEntity.getTransactionStatus(),
                        claimEntity.getTransactionChannel(),
                        claimEntity.getMaskedMerchant()),
                new ClaimFacts(
                        claimEntity.getIssueType(),
                        claimEntity.getStatement(),
                        claimEntity.getCardPossession(),
                        claimEntity.getMerchantRecognised()),
                caseEntity.getCreatedAt());
        fraudCase.restorePersistedState(caseEntity.getStatus());
        return fraudCase;
    }
}
