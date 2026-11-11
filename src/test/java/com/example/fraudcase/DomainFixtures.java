package com.example.fraudcase;

import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.ClaimFacts;
import com.example.fraudcase.domain.CustomerFacts;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ReportChannel;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionFacts;
import com.example.fraudcase.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Synthetic domain fixtures for Phase 2 tests. Masked values only.
 */
public final class DomainFixtures {

    private DomainFixtures() {
    }

    public static final String SAMPLE_CUSTOMER = "CUST-••••-4821";
    public static final String SAMPLE_TXN = "TXN-2026-784102";
    public static final String SAMPLE_MERCHANT = "NORTHSTAR DIGITAL ••••73";

    public static FraudCase fraudCase(IssueType issueType,
                                      MerchantRecognition merchantRecognised) {
        return fraudCase("FC-TEST0001", SAMPLE_CUSTOMER, SAMPLE_TXN, SAMPLE_MERCHANT,
                issueType, merchantRecognised);
    }

    public static FraudCase fraudCase(String caseId, String customerRef, String txnRef,
                                      String merchant, IssueType issueType,
                                      MerchantRecognition merchantRecognised) {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T09:15:00Z");
        return new FraudCase(
                caseId, now, ReportChannel.WEB_PORTAL, "unauthenticated-analyst",
                new CustomerFacts(customerRef, "IN-KA"),
                new TransactionFacts(txnRef, new BigDecimal("48750.00"), "INR",
                        now, TransactionStatus.POSTED,
                        TransactionChannel.CARD_NOT_PRESENT, merchant),
                new ClaimFacts(issueType,
                        "I do not recognise this purchase and still have my card.",
                        CardPossession.IN_POSSESSION, merchantRecognised),
                now);
    }

    public static ToolOutcome outcome(String tool, ToolStatus status, Map<String, String> attrs) {
        return new ToolOutcome(tool, provenanceFor(tool), status,
                "TR-TEST" + tool.hashCode(), OffsetDateTime.parse("2026-11-11T09:15:00Z"),
                attrs == null ? Map.of() : attrs, tool + " summary");
    }

    public static EvidenceItem evidence(String caseId, EvidenceProvenance provenance,
                                        EvidenceSeverity severity, String summary) {
        EvidenceType type = switch (provenance) {
            case CUSTOMER_INTAKE -> EvidenceType.CUSTOMER_CLAIM;
            case TRANSACTION_TOOL -> EvidenceType.TRANSACTION;
            case DEVICE_TOOL -> EvidenceType.DEVICE;
            case LOCATION_TOOL -> EvidenceType.LOCATION;
            case MERCHANT_TOOL -> EvidenceType.MERCHANT;
            case PRIOR_DISPUTE_TOOL -> EvidenceType.PRIOR_DISPUTE;
            case SLA_TOOL -> EvidenceType.SLA;
        };
        return new EvidenceItem("EVI-" + provenance.name() + "-1", caseId, type,
                severity, summary, provenance,
                OffsetDateTime.parse("2026-11-11T09:15:00Z"));
    }

    private static EvidenceProvenance provenanceFor(String tool) {
        return switch (tool) {
            case "getTransactionDetails" -> EvidenceProvenance.TRANSACTION_TOOL;
            case "getDeviceSignals" -> EvidenceProvenance.DEVICE_TOOL;
            case "compareRecentLocations" -> EvidenceProvenance.LOCATION_TOOL;
            case "getMerchantRiskProfile" -> EvidenceProvenance.MERCHANT_TOOL;
            case "getPriorDisputes" -> EvidenceProvenance.PRIOR_DISPUTE_TOOL;
            case "calculateCaseSla" -> EvidenceProvenance.SLA_TOOL;
            default -> throw new IllegalArgumentException("unknown tool " + tool);
        };
    }

    /** Standard outcomes mirroring the pinned sample fixture. */
    public static List<ToolOutcome> sampleCaseOutcomes(String authOutcome, String deviceKnown,
                                                       String trustLevel, String anomalyLevel) {
        return List.of(
                outcome("getTransactionDetails", ToolStatus.SUCCESS, Map.of(
                        "found", "true", "transactionStatus", "POSTED",
                        "occurredAt", "2026-11-06T08:42:00+05:30", "amount", "48750.00",
                        "channel", "CARD_NOT_PRESENT", "authOutcome", authOutcome,
                        "maskedMerchant", SAMPLE_MERCHANT, "duplicateFingerprintMatch", "false")),
                outcome("getDeviceSignals", ToolStatus.SUCCESS, Map.of(
                        "deviceKnown", deviceKnown, "firstSeenAt", "2026-11-06T08:40:00+05:30",
                        "trustLevel", trustLevel, "observations", "test")),
                outcome("compareRecentLocations", ToolStatus.SUCCESS, Map.of(
                        "distanceKm", "1450", "timeDeltaMinutes", "130",
                        "anomalyLevel", anomalyLevel, "observations", "test")),
                outcome("getMerchantRiskProfile", ToolStatus.SUCCESS, Map.of(
                        "riskBand", "MEDIUM", "blocklistMatch", "false",
                        "category", "DIGITAL_GOODS", "observations", "test")),
                outcome("getPriorDisputes", ToolStatus.SUCCESS, Map.of(
                        "count", "1", "similarCount", "0", "lookbackDays", "90",
                        "observations", "test")));
    }
}
