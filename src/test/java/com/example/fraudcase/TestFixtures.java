package com.example.fraudcase;

import com.example.fraudcase.api.FraudCaseIntakeRequest;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ConsentDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ReportChannel;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import com.example.fraudcase.observability.CaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Synthetic test fixtures. All values are masked/synthetic; no real
 * customer, card or account data may ever appear in tests.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static FraudCaseIntakeRequest validRequest() {
        return new FraudCaseIntakeRequest(
                OffsetDateTime.parse("2026-11-11T09:15:00Z"),
                ReportChannel.WEB_PORTAL,
                new CustomerDetails("CUST-••••-4821", "IN-KA"),
                new TransactionDetails(
                        "TXN-2026-784102",
                        new BigDecimal("48750.00"),
                        "INR",
                        OffsetDateTime.parse("2026-11-06T08:42:00+05:30"),
                        TransactionStatus.POSTED,
                        TransactionChannel.CARD_NOT_PRESENT,
                        "NORTHSTAR DIGITAL ••••73"),
                new ClaimDetails(
                        IssueType.MERCHANT_NOT_RECOGNISED,
                        "I do not recognise this purchase and still have my card.",
                        CardPossession.IN_POSSESSION,
                        MerchantRecognition.NOT_RECOGNISED),
                new ConsentDetails(true, true));
    }

    public static String validRequestJson() {
        return """
                {
                  "reportedAt": "2026-11-11T09:15:00Z",
                  "channel": "WEB_PORTAL",
                  "customer": {
                    "maskedCustomerRef": "CUST-\\u2022\\u2022\\u2022\\u2022-4821",
                    "homeRegion": "IN-KA"
                  },
                  "transaction": {
                    "transactionRef": "TXN-2026-784102",
                    "amount": 48750.00,
                    "currency": "INR",
                    "occurredAt": "2026-11-06T08:42:00+05:30",
                    "status": "POSTED",
                    "channel": "CARD_NOT_PRESENT",
                    "maskedMerchant": "NORTHSTAR DIGITAL \\u2022\\u2022\\u2022\\u202273"
                  },
                  "claim": {
                    "issueType": "MERCHANT_NOT_RECOGNISED",
                    "customerStatement": "I do not recognise this purchase and still have my card.",
                    "cardPossession": "IN_POSSESSION",
                    "merchantRecognised": "NOT_RECOGNISED"
                  },
                  "consent": {
                    "syntheticDataConfirmed": true,
                    "reviewRequired": true
                  }
                }
                """;
    }

    /** In-memory meter registry for service-level unit tests. */
    public static CaseMetrics testMetrics() {
        return new CaseMetrics(new SimpleMeterRegistry());
    }
}
