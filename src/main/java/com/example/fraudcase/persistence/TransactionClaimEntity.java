package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The customer's claim about one transaction. {@code statement} preserves
 * the original validated customer text verbatim — generated summaries must
 * never overwrite it.
 */
@Entity
@Table(name = "transaction_claim")
public class TransactionClaimEntity {

    @Id
    @Column(name = "claim_id", length = 32)
    private String claimId;

    @Column(name = "case_id", nullable = false, unique = true, length = 32)
    private String caseId;

    @Column(name = "transaction_ref", nullable = false, length = 40)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 40)
    private IssueType issueType;

    @Column(name = "statement", nullable = false, length = 2000)
    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_possession", nullable = false, length = 32)
    private CardPossession cardPossession;

    @Enumerated(EnumType.STRING)
    @Column(name = "merchant_recognised", nullable = false, length = 32)
    private MerchantRecognition merchantRecognised;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_status", nullable = false, length = 32)
    private TransactionStatus transactionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_channel", nullable = false, length = 32)
    private TransactionChannel transactionChannel;

    @Column(name = "masked_merchant", nullable = false, length = 80)
    private String maskedMerchant;

    protected TransactionClaimEntity() {
    }

    public TransactionClaimEntity(String claimId, String caseId, String transactionRef,
                                  IssueType issueType, String statement,
                                  CardPossession cardPossession,
                                  MerchantRecognition merchantRecognised,
                                  BigDecimal amount, String currency,
                                  OffsetDateTime occurredAt,
                                  TransactionStatus transactionStatus,
                                  TransactionChannel transactionChannel,
                                  String maskedMerchant) {
        this.claimId = claimId;
        this.caseId = caseId;
        this.transactionRef = transactionRef;
        this.issueType = issueType;
        this.statement = statement;
        this.cardPossession = cardPossession;
        this.merchantRecognised = merchantRecognised;
        this.amount = amount;
        this.currency = currency;
        this.occurredAt = occurredAt;
        this.transactionStatus = transactionStatus;
        this.transactionChannel = transactionChannel;
        this.maskedMerchant = maskedMerchant;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public IssueType getIssueType() {
        return issueType;
    }

    public String getStatement() {
        return statement;
    }

    public CardPossession getCardPossession() {
        return cardPossession;
    }

    public MerchantRecognition getMerchantRecognised() {
        return merchantRecognised;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public TransactionStatus getTransactionStatus() {
        return transactionStatus;
    }

    public TransactionChannel getTransactionChannel() {
        return transactionChannel;
    }

    public String getMaskedMerchant() {
        return maskedMerchant;
    }
}
