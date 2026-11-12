package com.example.fraudcase.ui;

import com.example.fraudcase.api.FraudCaseIntakeRequest;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ConsentDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import com.example.fraudcase.api.validation.IsoCurrency;
import com.example.fraudcase.api.validation.MaskedIdentifier;
import com.example.fraudcase.api.validation.MaskedMerchantValue;
import com.example.fraudcase.api.validation.NoUnsafeMarkup;
import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ReportChannel;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Form bean for the Case Intake screen. Mirrors the Phase 1 intake
 * contract and reuses the same constraint annotations — validation is
 * identical to the JSON API. {@code reportedAt} and {@code occurredAt}
 * are bound from {@code datetime-local} controls; the application supplies
 * {@code reportedAt} itself. Server-managed fields (caseId, analystId)
 * deliberately do not exist here.
 */
public class IntakeForm {

    @NotBlank
    @MaskedIdentifier
    private String maskedCustomerRef;

    @NotBlank
    @Size(max = 100)
    @NoUnsafeMarkup
    private String homeRegion;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{2,8}-[A-Z0-9-]{2,32}$",
            message = "must be a synthetic reference such as TXN-2026-784102")
    private String transactionRef;

    @NotNull
    @Positive
    @Digits(integer = 13, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @IsoCurrency
    private String currency;

    @NotNull
    @PastOrPresent
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime occurredAt;

    @NotNull
    private TransactionStatus status;

    @NotNull
    private TransactionChannel channel;

    @NotBlank
    @MaskedMerchantValue
    @NoUnsafeMarkup
    private String maskedMerchant;

    @NotNull
    private IssueType issueType;

    @NotBlank
    @Size(max = FraudCaseIntakeRequest.CUSTOMER_STATEMENT_MAX_LENGTH)
    @NoUnsafeMarkup
    private String customerStatement;

    @NotNull
    private CardPossession cardPossession;

    @NotNull
    private MerchantRecognition merchantRecognised;

    @NotNull
    private ReportChannel reportChannel;

    @AssertTrue(message = "must be true: synthetic training data only")
    private boolean syntheticDataConfirmed;

    @AssertTrue(message = "must be true: every case requires human review")
    private boolean reviewRequired;

    /** UI intent: "create" (validate+persist) or "investigate" (create+start). */
    private String intent = "create";

    public FraudCaseIntakeRequest toRequest(OffsetDateTime reportedAt) {
        return new FraudCaseIntakeRequest(
                reportedAt,
                reportChannel,
                new CustomerDetails(maskedCustomerRef, homeRegion),
                new TransactionDetails(
                        transactionRef, amount, currency,
                        occurredAt.atZone(ZoneId.systemDefault())
                                .toOffsetDateTime(),
                        status, channel, maskedMerchant),
                new ClaimDetails(issueType, customerStatement,
                        cardPossession, merchantRecognised),
                new ConsentDetails(syntheticDataConfirmed, reviewRequired));
    }

    public String getMaskedCustomerRef() {
        return maskedCustomerRef;
    }

    public void setMaskedCustomerRef(String maskedCustomerRef) {
        this.maskedCustomerRef = maskedCustomerRef;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public void setTransactionRef(String transactionRef) {
        this.transactionRef = transactionRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public TransactionChannel getChannel() {
        return channel;
    }

    public void setChannel(TransactionChannel channel) {
        this.channel = channel;
    }

    public String getMaskedMerchant() {
        return maskedMerchant;
    }

    public void setMaskedMerchant(String maskedMerchant) {
        this.maskedMerchant = maskedMerchant;
    }

    public IssueType getIssueType() {
        return issueType;
    }

    public void setIssueType(IssueType issueType) {
        this.issueType = issueType;
    }

    public String getCustomerStatement() {
        return customerStatement;
    }

    public void setCustomerStatement(String customerStatement) {
        this.customerStatement = customerStatement;
    }

    public CardPossession getCardPossession() {
        return cardPossession;
    }

    public void setCardPossession(CardPossession cardPossession) {
        this.cardPossession = cardPossession;
    }

    public MerchantRecognition getMerchantRecognised() {
        return merchantRecognised;
    }

    public void setMerchantRecognised(MerchantRecognition merchantRecognised) {
        this.merchantRecognised = merchantRecognised;
    }

    public ReportChannel getReportChannel() {
        return reportChannel;
    }

    public void setReportChannel(ReportChannel reportChannel) {
        this.reportChannel = reportChannel;
    }

    public boolean isSyntheticDataConfirmed() {
        return syntheticDataConfirmed;
    }

    public void setSyntheticDataConfirmed(boolean syntheticDataConfirmed) {
        this.syntheticDataConfirmed = syntheticDataConfirmed;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }

    public void setReviewRequired(boolean reviewRequired) {
        this.reviewRequired = reviewRequired;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }
}
