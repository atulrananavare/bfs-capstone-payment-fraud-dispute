package com.example.fraudcase.agent.rules;

import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.ClaimFacts;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.domain.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic rules processor. No LLM, no credentials, no model provider,
 * no RAG — pure function over the validated case facts and tool outcomes
 * supplied in {@link FraudCaseContext}.
 *
 * <p><b>Rule precedence</b> (evaluated in order, first match wins):
 * <ol>
 *   <li><b>R001</b> mandatory transaction evidence absent/not found →
 *       INSUFFICIENT_EVIDENCE + MANUAL_REVIEW</li>
 *   <li><b>R002</b> confirmed duplicate fingerprint →
 *       DUPLICATE_PROCESSING + PAYMENT_OPERATIONS</li>
 *   <li><b>R004</b> recognised merchant + fulfilment/cancellation/amount
 *       issue → MERCHANT_DISPUTE + DISPUTE_OPERATIONS</li>
 *   <li><b>R003</b> ≥2 independently sourced high-severity fraud anomalies →
 *       UNAUTHORISED_TRANSACTION (customer denies) or SUSPICIOUS_TRANSACTION
 *       + FRAUD_INVESTIGATION + HIGH</li>
 *   <li><b>R006</b> denial claim contradicted by ≥2 benign independent
 *       signals → INSUFFICIENT_EVIDENCE + MANUAL_REVIEW</li>
 *   <li><b>R007</b> exactly 1 high-severity anomaly → SUSPICIOUS_TRANSACTION
 *       + FRAUD_INVESTIGATION + MEDIUM</li>
 *   <li><b>R008</b> no anomalies → GENUINE_TRANSACTION + MANUAL_REVIEW + LOW</li>
 * </ol>
 * R005 (policy grounding gap) fires after the substantive rules when a
 * policy context was requested for this investigation and none is usable:
 * route forced to MANUAL_REVIEW, confidence capped, and the gap recorded —
 * policy is never faked to cover the absence.
 *
 * <p><b>Confidence</b> is a deterministic rule score (documented per rule),
 * not a statistical probability: each unavailable non-mandatory tool reduces
 * it by 0.05 with a floor of 0.05.
 */
@Component
public class RulesFraudCaseAgent implements FraudCaseProcessor {

    public static final Set<String> RULE_IDS =
            Set.of("R001", "R002", "R003", "R004", "R005", "R006", "R007", "R008");

    private static final double MISSING_EVIDENCE_PENALTY = 0.05;
    private static final double CONFIDENCE_FLOOR = 0.05;

    private static final List<String> EXPECTED_TOOLS = List.of(
            "getTransactionDetails",
            "getDeviceSignals",
            "compareRecentLocations",
            "getMerchantRiskProfile",
            "getPriorDisputes");

    private record Anomaly(EvidenceProvenance source, String fact) {
    }

    @Override
    public FraudCaseRecommendation analyze(FraudCaseContext context) {
        return applyR005(evaluate(context), context);
    }

    private FraudCaseRecommendation evaluate(FraudCaseContext context) {
        FraudCase fraudCase = context.fraudCase();
        ClaimFacts claim = fraudCase.claim();
        List<String> missing = new ArrayList<>();

        // Per-tool availability → missing information (never invented).
        for (String tool : EXPECTED_TOOLS) {
            ToolOutcome outcome = context.outcomeOf(tool);
            if (outcome == null) {
                missing.add(tool + " did not execute");
            } else if (outcome.status() != ToolStatus.SUCCESS) {
                missing.add(tool + " evidence unavailable (" + outcome.status() + ")");
            }
        }

        ToolOutcome tx = context.outcomeOf("getTransactionDetails");
        ToolOutcome device = context.outcomeOf("getDeviceSignals");
        ToolOutcome location = context.outcomeOf("compareRecentLocations");
        ToolOutcome merchant = context.outcomeOf("getMerchantRiskProfile");

        boolean txFound = tx != null && tx.isSuccessful()
                && "true".equals(tx.attribute("found"));

        // ---- R001: mandatory transaction evidence absent ----
        if (!txFound) {
            missing.add("transaction details unavailable or not found");
            return build(fraudCase, CaseClassification.INSUFFICIENT_EVIDENCE,
                    RecommendedRoute.MANUAL_REVIEW, RiskLevel.MEDIUM, 0.30,
                    context, List.of(), List.of("R001"), missing,
                    "R001: mandatory transaction evidence is absent or not found; "
                            + "cannot safely classify. Routing to manual review.");
        }

        // Authentication outcome may be legitimately unavailable — record as
        // missing information, never invented.
        if ("UNAVAILABLE".equals(tx.attribute("authOutcome"))) {
            missing.add("transaction authentication outcome unavailable");
        }

        // ---- R002: confirmed duplicate fingerprint ----
        if ("true".equals(tx.attribute("duplicateFingerprintMatch"))) {
            return build(fraudCase, CaseClassification.DUPLICATE_PROCESSING,
                    RecommendedRoute.PAYMENT_OPERATIONS, RiskLevel.MEDIUM, 0.95,
                    context, List.of(EvidenceProvenance.TRANSACTION_TOOL), List.of("R002"), missing,
                    "R002: transaction evidence confirms a duplicate processing "
                            + "fingerprint (duplicateFingerprintMatch=true). Routing to payment operations.");
        }

        boolean customerDenies = claim.merchantRecognised() == MerchantRecognition.NOT_RECOGNISED
                || claim.issueType() == IssueType.MERCHANT_NOT_RECOGNISED
                || claim.issueType() == IssueType.UNAUTHORISED_TRANSACTION;

        // ---- R004: recognised merchant + fulfilment/cancellation/amount ----
        if (claim.merchantRecognised() == MerchantRecognition.RECOGNISED
                && (claim.issueType() == IssueType.AMOUNT_DISPUTE
                || claim.issueType() == IssueType.CANCELLED_SERVICE
                || claim.issueType() == IssueType.NON_RECEIPT)) {
            return build(fraudCase, CaseClassification.MERCHANT_DISPUTE,
                    RecommendedRoute.DISPUTE_OPERATIONS, RiskLevel.MEDIUM,
                    adjusted(0.80, missing),
                    context, List.of(EvidenceProvenance.CUSTOMER_INTAKE), List.of("R004"), missing,
                    "R004: customer recognises the merchant and the issue concerns "
                            + claim.issueType() + ". Routing to dispute operations.");
        }

        // ---- Collect independently sourced high-severity anomalies ----
        List<Anomaly> anomalies = new ArrayList<>();
        if (device != null && device.isSuccessful()) {
            if ("false".equals(device.attribute("deviceKnown"))) {
                anomalies.add(new Anomaly(EvidenceProvenance.DEVICE_TOOL,
                        "device not previously associated with customer (deviceKnown=false)"));
            }
            if ("UNTRUSTED".equals(device.attribute("trustLevel"))) {
                anomalies.add(new Anomaly(EvidenceProvenance.DEVICE_TOOL,
                        "device trust level UNTRUSTED"));
            }
        }
        if (location != null && location.isSuccessful()
                && "HIGH".equals(location.attribute("anomalyLevel"))) {
            anomalies.add(new Anomaly(EvidenceProvenance.LOCATION_TOOL,
                    "location anomalyLevel=HIGH, distanceKm=" + location.attribute("distanceKm")));
        }
        if ("FAILED".equals(tx.attribute("authOutcome"))) {
            anomalies.add(new Anomaly(EvidenceProvenance.TRANSACTION_TOOL,
                    "authentication outcome FAILED"));
        }
        if (merchant != null && merchant.isSuccessful()) {
            if ("true".equals(merchant.attribute("blocklistMatch"))) {
                anomalies.add(new Anomaly(EvidenceProvenance.MERCHANT_TOOL,
                        "merchant blocklist match"));
            }
            if ("HIGH".equals(merchant.attribute("riskBand"))) {
                anomalies.add(new Anomaly(EvidenceProvenance.MERCHANT_TOOL,
                        "merchant riskBand=HIGH"));
            }
        }
        // Independence: distinct provenance sources only.
        long independentSources = anomalies.stream()
                .map(Anomaly::source).distinct().count();

        // ---- R003: multiple independent high-severity anomalies ----
        if (independentSources >= 2) {
            CaseClassification classification = customerDenies
                    ? CaseClassification.UNAUTHORISED_TRANSACTION
                    : CaseClassification.SUSPICIOUS_TRANSACTION;
            List<EvidenceProvenance> sources = anomalies.stream()
                    .map(Anomaly::source).distinct().toList();
            String facts = anomalies.stream().map(Anomaly::fact)
                    .reduce((a, b) -> a + "; " + b).orElse("");
            return build(fraudCase, classification,
                    RecommendedRoute.FRAUD_INVESTIGATION, RiskLevel.HIGH,
                    adjusted(customerDenies ? 0.85 : 0.75, missing),
                    context, sources, List.of("R003"), missing,
                    "R003: " + independentSources + " independently sourced high-severity "
                            + "anomalies (" + facts + ")"
                            + (customerDenies
                            ? "; customer denies the transaction/merchant."
                            : "; customer claim does not establish unauthorised use.")
                            + " Routing to fraud investigation.");
        }

        // ---- R006: denial contradicted by benign independent signals ----
        List<String> benignFacts = new ArrayList<>();
        if (device != null && device.isSuccessful()
                && "TRUSTED".equals(device.attribute("trustLevel"))) {
            benignFacts.add("device trusted");
        }
        if (location != null && location.isSuccessful()
                && "LOW".equals(location.attribute("anomalyLevel"))) {
            benignFacts.add("location consistent with trusted activity");
        }
        if ("SUCCESS".equals(tx.attribute("authOutcome"))) {
            benignFacts.add("authentication succeeded");
        }
        if (customerDenies && benignFacts.size() >= 2) {
            missing.add("contradictory evidence: customer denies the transaction but "
                    + String.join("; ", benignFacts));
            return build(fraudCase, CaseClassification.INSUFFICIENT_EVIDENCE,
                    RecommendedRoute.MANUAL_REVIEW, RiskLevel.MEDIUM, 0.25,
                    context,
                    List.of(EvidenceProvenance.CUSTOMER_INTAKE, EvidenceProvenance.DEVICE_TOOL,
                            EvidenceProvenance.LOCATION_TOOL, EvidenceProvenance.TRANSACTION_TOOL),
                    List.of("R006"), missing,
                    "R006: claim of unauthorised use is contradicted by independent benign "
                            + "signals (" + String.join("; ", benignFacts)
                            + "). Evidence is contradictory; routing to manual review.");
        }

        // ---- R007: exactly one high-severity anomaly ----
        if (anomalies.size() == 1) {
            Anomaly single = anomalies.get(0);
            missing.add("insufficient independent corroboration: only one anomaly source ("
                    + single.source() + ")");
            return build(fraudCase, CaseClassification.SUSPICIOUS_TRANSACTION,
                    RecommendedRoute.FRAUD_INVESTIGATION, RiskLevel.MEDIUM,
                    adjusted(0.55, missing),
                    context, List.of(single.source()), List.of("R007"), missing,
                    "R007: a single high-severity anomaly (" + single.fact()
                            + ") without independent corroboration. Routing to fraud "
                            + "investigation with reduced confidence.");
        }

        // ---- R008: no anomalies ----
        return build(fraudCase, CaseClassification.GENUINE_TRANSACTION,
                RecommendedRoute.MANUAL_REVIEW, RiskLevel.LOW,
                adjusted(0.70, missing),
                context, List.of(EvidenceProvenance.CUSTOMER_INTAKE), List.of("R008"), missing,
                "R008: no high-severity anomalies detected and no dispute pattern matched; "
                        + "evidence supports a lower-risk outcome. Routing to manual review.");
    }

    /**
     * R005 — policy grounding gap. When an investigation requested policy
     * context and none is usable (nothing matched, or retrieval failed), no
     * substantive route may stand: the case is routed to manual review with
     * the gap recorded. Fires only in flows that actually performed
     * retrieval; pure rules mode sees NOT_REQUESTED and is untouched.
     */
    private FraudCaseRecommendation applyR005(
            FraudCaseRecommendation rec, FraudCaseContext context) {
        if (context.policyStatus() != PolicyContextStatus.NO_MATCH
                && context.policyStatus() != PolicyContextStatus.UNAVAILABLE) {
            return rec;
        }
        List<String> ruleIds = new ArrayList<>(rec.ruleIds());
        ruleIds.add("R005");
        List<String> missing = new ArrayList<>(rec.missingInformation());
        missing.add("valid applicable policy guidance unavailable; "
                + "manual review required");
        String marker = context.policyStatus() == PolicyContextStatus.NO_MATCH
                ? "no applicable policy passage matched"
                : "policy retrieval was unavailable";
        return new FraudCaseRecommendation(
                rec.caseId(), rec.classification(), rec.riskLevel(),
                RecommendedRoute.MANUAL_REVIEW,
                Math.min(rec.confidence(), 0.50),
                true, rec.evidenceIds(), rec.policyCitations(),
                List.copyOf(missing), List.copyOf(ruleIds),
                rec.toolTraceIds(),
                rec.rationale() + " R005: " + marker
                        + "; routing to manual review rather than proceeding "
                        + "without policy guidance.");
    }

    private FraudCaseRecommendation build(
            FraudCase fraudCase,
            CaseClassification classification,
            RecommendedRoute route,
            RiskLevel risk,
            double confidence,
            FraudCaseContext context,
            List<EvidenceProvenance> supportingSources,
            List<String> ruleIds,
            List<String> missing,
            String rationale) {
        Set<String> evidenceIds = new LinkedHashSet<>();
        for (EvidenceItem item : context.evidence()) {
            if (supportingSources.isEmpty()
                    || supportingSources.contains(item.provenance())) {
                evidenceIds.add(item.evidenceId());
            }
        }
        return new FraudCaseRecommendation(
                fraudCase.caseId(),
                classification,
                risk,
                route,
                confidence,
                true, // humanReviewRequired — always
                List.copyOf(evidenceIds),
                List.of(), // policyCitations — RAG arrives in a later phase; never faked
                List.copyOf(missing),
                ruleIds,
                context.toolTraceIds(),
                rationale);
    }

    /**
     * Deterministic confidence adjustment: −0.05 per unavailable
     * non-mandatory tool, floored at 0.05.
     */
    private double adjusted(double base, List<String> missing) {
        long unavailableTools = missing.stream()
                .filter(m -> m.endsWith("did not execute") || m.contains("evidence unavailable"))
                .count();
        double value = base - unavailableTools * MISSING_EVIDENCE_PENALTY;
        return Math.max(CONFIDENCE_FLOOR, Math.round(value * 100.0) / 100.0);
    }
}
