package com.example.fraudcase.rag;

import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.domain.TransactionChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the retrieval query from case facts and collected tool outcomes.
 * Only derived, canonical descriptors are used — no customer statement,
 * no identifiers, nothing sensitive reaches the query or the audit log.
 */
public final class PolicyQueryBuilder {

    private static final String GOAL =
            "determine correct triage routing and required evidence";

    private PolicyQueryBuilder() {
    }

    public static PolicyRetrievalRequest fromContext(
            FraudCase fraudCase, List<ToolOutcome> outcomes) {
        List<String> descriptors = new ArrayList<>();
        if (fraudCase.transaction().channel() == TransactionChannel.CARD_NOT_PRESENT) {
            descriptors.add("card not present transaction");
        } else {
            descriptors.add("posted transaction");
        }
        IssueType issue = fraudCase.claim().issueType();
        if (issue == IssueType.UNAUTHORISED_TRANSACTION
                || issue == IssueType.MERCHANT_NOT_RECOGNISED
                || fraudCase.claim().merchantRecognised()
                        == MerchantRecognition.NOT_RECOGNISED) {
            descriptors.add("customer denies unauthorised transaction");
            descriptors.add("merchant not recognised");
        }
        if (fraudCase.claim().merchantRecognised() == MerchantRecognition.RECOGNISED) {
            descriptors.add("merchant recognised by customer");
            descriptors.add("merchant dispute complaint");
        }
        if (issue == IssueType.CANCELLED_SERVICE) {
            descriptors.add("service cancelled but charged");
            descriptors.add("fulfilment cancellation dispute operations");
        }
        if (issue == IssueType.NON_RECEIPT) {
            descriptors.add("goods or services not received");
            descriptors.add("fulfilment non-receipt dispute operations");
        }
        if (issue == IssueType.AMOUNT_DISPUTE) {
            descriptors.add("amount dispute discrepancy");
            descriptors.add("amount issue dispute operations");
        }
        if (issue == IssueType.DUPLICATE_CHARGE) {
            descriptors.add("duplicate charge");
            descriptors.add("duplicate processing posting review");
        }
        descriptors.addAll(evidenceDescriptors(outcomes));
        return new PolicyRetrievalRequest(
                fraudCase.caseId(), descriptors, GOAL);
    }

    private static List<String> evidenceDescriptors(List<ToolOutcome> outcomes) {
        List<String> descriptors = new ArrayList<>();
        for (ToolOutcome outcome : outcomes == null
                ? List.<ToolOutcome>of() : outcomes) {
            if (!outcome.isSuccessful()) {
                continue;
            }
            switch (outcome.toolName()) {
                case "getTransactionDetails" -> {
                    if ("UNAVAILABLE".equals(outcome.attribute("authOutcome"))) {
                        descriptors.add("authentication outcome unavailable");
                    } else if ("FAILED".equals(outcome.attribute("authOutcome"))) {
                        descriptors.add("authentication failed");
                    }
                    if ("true".equals(
                            outcome.attribute("duplicateFingerprintMatch"))) {
                        descriptors.add("duplicate posting fingerprint confirmed");
                    }
                }
                case "getDeviceSignals" -> {
                    if ("false".equals(outcome.attribute("deviceKnown"))) {
                        descriptors.add("new unknown device");
                    }
                    if ("UNTRUSTED".equals(outcome.attribute("trustLevel"))) {
                        descriptors.add("untrusted device");
                    } else if ("TRUSTED".equals(outcome.attribute("trustLevel"))) {
                        descriptors.add("trusted device");
                    }
                }
                case "compareRecentLocations" -> {
                    if ("HIGH".equals(outcome.attribute("anomalyLevel"))) {
                        descriptors.add("high location anomaly");
                    } else if ("LOW".equals(outcome.attribute("anomalyLevel"))) {
                        descriptors.add("consistent location");
                    }
                }
                case "getMerchantRiskProfile" -> {
                    if ("true".equals(outcome.attribute("blocklistMatch"))) {
                        descriptors.add("merchant blocklist match");
                    }
                    if ("HIGH".equals(outcome.attribute("riskBand"))) {
                        descriptors.add("high risk merchant");
                    }
                }
                case "getPriorDisputes" -> {
                    if (!"0".equals(outcome.attribute("count"))
                            && outcome.attribute("count") != null) {
                        descriptors.add("prior dispute history");
                    }
                }
                default -> { }
            }
        }
        return descriptors;
    }

    /** The raw query string sent to the store. */
    public static String queryText(PolicyRetrievalRequest request) {
        return String.join("; ", request.descriptors())
                + "; " + request.investigationGoal();
    }
}
