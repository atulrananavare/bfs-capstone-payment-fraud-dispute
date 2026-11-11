package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ConsentDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseIntakeRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Set<ConstraintViolation<FraudCaseIntakeRequest>> validate(FraudCaseIntakeRequest request) {
        return validator.validate(request);
    }

    private static Set<String> violatedFields(FraudCaseIntakeRequest request) {
        return validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static FraudCaseIntakeRequest withCustomer(CustomerDetails customer) {
        FraudCaseIntakeRequest base = TestFixtures.validRequest();
        return new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), customer,
                base.transaction(), base.claim(), base.consent());
    }

    private static FraudCaseIntakeRequest withTransaction(TransactionDetails transaction) {
        FraudCaseIntakeRequest base = TestFixtures.validRequest();
        return new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                transaction, base.claim(), base.consent());
    }

    private static FraudCaseIntakeRequest withClaim(ClaimDetails claim) {
        FraudCaseIntakeRequest base = TestFixtures.validRequest();
        return new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                base.transaction(), claim, base.consent());
    }

    private static FraudCaseIntakeRequest withConsent(ConsentDetails consent) {
        FraudCaseIntakeRequest base = TestFixtures.validRequest();
        return new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                base.transaction(), base.claim(), consent);
    }

    @Test
    void validSyntheticIntakeIsAccepted() {
        assertThat(validate(TestFixtures.validRequest())).isEmpty();
    }

    @Test
    void missingMandatorySectionIsRejected() {
        FraudCaseIntakeRequest base = TestFixtures.validRequest();
        FraudCaseIntakeRequest missingCustomer = new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), null,
                base.transaction(), base.claim(), base.consent());
        assertThat(violatedFields(missingCustomer)).contains("customer");

        FraudCaseIntakeRequest missingChannel = new FraudCaseIntakeRequest(
                base.reportedAt(), null, base.customer(),
                base.transaction(), base.claim(), base.consent());
        assertThat(violatedFields(missingChannel)).contains("channel");
    }

    @Test
    void nonPositiveAmountIsRejected() {
        TransactionDetails valid = TestFixtures.validRequest().transaction();
        for (BigDecimal amount : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-1.00")}) {
            TransactionDetails t = new TransactionDetails(
                    valid.transactionRef(), amount, valid.currency(), valid.occurredAt(),
                    valid.status(), valid.channel(), valid.maskedMerchant());
            assertThat(violatedFields(withTransaction(t))).contains("transaction.amount");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"XX9", "XX", "US DOLLAR", "usD", "XXX", "123"})
    void invalidCurrencyIsRejected(String currency) {
        TransactionDetails valid = TestFixtures.validRequest().transaction();
        TransactionDetails t = new TransactionDetails(
                valid.transactionRef(), valid.amount(), currency, valid.occurredAt(),
                valid.status(), valid.channel(), valid.maskedMerchant());
        assertThat(violatedFields(withTransaction(t))).contains("transaction.currency");
    }

    @Test
    void overlongCustomerStatementIsRejected() {
        ClaimDetails valid = TestFixtures.validRequest().claim();
        ClaimDetails claim = new ClaimDetails(
                valid.issueType(),
                "x".repeat(FraudCaseIntakeRequest.CUSTOMER_STATEMENT_MAX_LENGTH + 1),
                valid.cardPossession(),
                valid.merchantRecognised());
        assertThat(violatedFields(withClaim(claim))).contains("claim.customerStatement");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I dispute this <script>alert(1)</script> charge",
            "please see <img src=x onerror=alert(1)>",
            "click javascript:alert(1) now",
            "sent data to data:text/html;base64,PGI+",
            "<DIV>markup</DIV>"
    })
    void scriptOrMarkupStatementIsRejected(String statement) {
        ClaimDetails valid = TestFixtures.validRequest().claim();
        ClaimDetails claim = new ClaimDetails(
                valid.issueType(), statement,
                valid.cardPossession(), valid.merchantRecognised());
        assertThat(violatedFields(withClaim(claim))).contains("claim.customerStatement");
    }

    @Test
    void syntheticDataNotConfirmedIsRejected() {
        assertThat(violatedFields(withConsent(new ConsentDetails(false, true))))
                .contains("consent.syntheticDataConfirmed");
    }

    @Test
    void reviewNotRequiredIsRejected() {
        assertThat(violatedFields(withConsent(new ConsentDetails(true, false))))
                .contains("consent.reviewRequired");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111111111111111",
            "4111-1111-1111-1111",
            "4111 1111 1111 1111",
            "CUST-123456789012",
            "CUST-4821"
    })
    void unmaskedCustomerReferenceIsRejected(String ref) {
        assertThat(violatedFields(withCustomer(new CustomerDetails(ref, "IN-KA"))))
                .contains("customer.maskedCustomerRef");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CUST-••••-4821",
            "CUST-****-4821",
            "CUST-XXXX-4821",
            "tok_cust_xx9942"
    })
    void maskedCustomerReferenceIsAccepted(String ref) {
        assertThat(validate(withCustomer(new CustomerDetails(ref, "IN-KA")))).isEmpty();
    }

    @Test
    void unmaskedMerchantIdentifierIsRejected() {
        TransactionDetails valid = TestFixtures.validRequest().transaction();
        TransactionDetails t = new TransactionDetails(
                valid.transactionRef(), valid.amount(), valid.currency(), valid.occurredAt(),
                valid.status(), valid.channel(), "NORTHSTAR DIGITAL 4111111111111111");
        assertThat(violatedFields(withTransaction(t))).contains("transaction.maskedMerchant");
    }

    @Test
    void rawPanAsTransactionReferenceIsRejected() {
        TransactionDetails valid = TestFixtures.validRequest().transaction();
        TransactionDetails t = new TransactionDetails(
                "4111111111111111", valid.amount(), valid.currency(), valid.occurredAt(),
                valid.status(), valid.channel(), valid.maskedMerchant());
        assertThat(violatedFields(withTransaction(t))).contains("transaction.transactionRef");
    }
}
