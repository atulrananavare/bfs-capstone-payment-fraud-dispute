package com.example.fraudcase.tools;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for all six deterministic evidence tools:
 * typed valid request, invalid request, deterministic repeatability,
 * provenance and structured failure — for every tool.
 */
class DeterministicToolsTest {

    private CapstoneProperties properties;
    private Clock clock;

    private TransactionDetailsTool transactionTool;
    private DeviceSignalsTool deviceTool;
    private CompareRecentLocationsTool locationTool;
    private MerchantRiskProfileTool merchantTool;
    private PriorDisputesTool priorDisputesTool;
    private CaseSlaTool slaTool;

    @BeforeEach
    void setUp() {
        properties = new CapstoneProperties();
        clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        transactionTool = new TransactionDetailsTool(properties, clock);
        deviceTool = new DeviceSignalsTool(properties, clock);
        locationTool = new CompareRecentLocationsTool(properties, clock);
        merchantTool = new MerchantRiskProfileTool(properties, clock);
        priorDisputesTool = new PriorDisputesTool(properties, clock);
        slaTool = new CaseSlaTool(properties, clock);
    }

    // ---------- getTransactionDetails ----------

    @Test
    void transactionDetailsValidRequestSucceeds() {
        var result = transactionTool.execute(
                new TransactionDetailsTool.Request("TXN-2026-784102"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.found()).isTrue();
        assertThat(result.transactionStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(result.channel()).isEqualTo(TransactionChannel.CARD_NOT_PRESENT);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.TRANSACTION_TOOL);
        assertThat(result.toolResultId()).startsWith("TR-");
        assertThat(result.observedAt()).isNotNull();
    }

    @Test
    void transactionDetailsNotFound() {
        var result = transactionTool.execute(
                new TransactionDetailsTool.Request("TXN-4040-404040"));
        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(result.found()).isFalse();
        assertThat(result.toEvidence("FC-X")).isEmpty();
    }

    @Test
    void transactionDetailsInvalidRequest() {
        assertThat(transactionTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(transactionTool.execute(new TransactionDetailsTool.Request("bad ref!!"))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(transactionTool.execute(new TransactionDetailsTool.Request("4111111111111111"))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
    }

    @Test
    void transactionDetailsDeterministic() {
        var a = transactionTool.execute(new TransactionDetailsTool.Request("TXN-2026-784102"));
        var b = transactionTool.execute(new TransactionDetailsTool.Request("TXN-2026-784102"));
        assertThat(a.toolResultId()).isEqualTo(b.toolResultId());
        assertThat(a.attributes()).isEqualTo(b.attributes());
        assertThat(a.transactionStatus()).isEqualTo(b.transactionStatus());
        assertThat(a.authOutcome()).isEqualTo(b.authOutcome());
    }

    @Test
    void transactionDetailsDifferentInputsCanDiffer() {
        var a = transactionTool.execute(new TransactionDetailsTool.Request("TXN-2026-784102"));
        var b = transactionTool.execute(new TransactionDetailsTool.Request("TXN-4040-404040"));
        assertThat(a.status()).isNotEqualTo(b.status());
    }

    @Test
    void sampleCaseAuthOutcomeIsUnavailableNotInvented() {
        var result = transactionTool.execute(
                new TransactionDetailsTool.Request("TXN-2026-784102"));
        assertThat(result.authOutcome())
                .isEqualTo(TransactionDetailsTool.AuthOutcome.UNAVAILABLE);
    }

    // ---------- getDeviceSignals ----------

    @Test
    void deviceSignalsValidRequest() {
        var result = deviceTool.execute(new DeviceSignalsTool.Request("CUST-••••-4821"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.DEVICE_TOOL);
        assertThat(result.trustLevel()).isNotNull();
    }

    @Test
    void deviceSignalsSampleCaseIsNewDevice() {
        var result = deviceTool.execute(new DeviceSignalsTool.Request("CUST-••••-4821"));
        assertThat(result.deviceKnown()).isFalse();
        assertThat(result.trustLevel())
                .isEqualTo(DeviceSignalsTool.DeviceTrustLevel.NEW_DEVICE);
        assertThat(result.firstSeenAt()).isNotNull();
    }

    @Test
    void deviceSignalsInvalidRequest() {
        assertThat(deviceTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(deviceTool.execute(new DeviceSignalsTool.Request("4111111111111111"))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(deviceTool.execute(new DeviceSignalsTool.Request("CUST-4821"))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST); // no mask token
    }

    @Test
    void deviceSignalsDeterministicAndDifferent() {
        var a = deviceTool.execute(new DeviceSignalsTool.Request("CUST-••••-4821"));
        var b = deviceTool.execute(new DeviceSignalsTool.Request("CUST-••••-4821"));
        assertThat(a.attributes()).isEqualTo(b.attributes());
        assertThat(a.toolResultId()).isEqualTo(b.toolResultId());

        Set<String> trustOutcomes = new HashSet<>();
        for (String ref : List.of("CUST-••••-0001", "CUST-••••-0002", "CUST-••••-0003",
                "CUST-••••-0004", "CUST-••••-0005", "CUST-••••-0006")) {
            var r = deviceTool.execute(new DeviceSignalsTool.Request(ref));
            trustOutcomes.add(r.deviceKnown() + "|" + r.trustLevel());
        }
        assertThat(trustOutcomes.size()).isGreaterThan(1);
    }

    // ---------- compareRecentLocations ----------

    @Test
    void locationsValidRequest() {
        var result = locationTool.execute(
                new CompareRecentLocationsTool.Request("CUST-••••-4821"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.LOCATION_TOOL);
    }

    @Test
    void locationsSampleCaseIsHighAnomaly() {
        var result = locationTool.execute(
                new CompareRecentLocationsTool.Request("CUST-••••-4821"));
        assertThat(result.anomalyLevel())
                .isEqualTo(CompareRecentLocationsTool.AnomalyLevel.HIGH);
        assertThat(result.distanceKm()).isEqualTo(1450);
    }

    @Test
    void locationsInvalidRequest() {
        assertThat(locationTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(locationTool.execute(new CompareRecentLocationsTool.Request(""))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
    }

    @Test
    void locationsDeterministicAndVaried() {
        var a = locationTool.execute(new CompareRecentLocationsTool.Request("CUST-••••-0001"));
        var b = locationTool.execute(new CompareRecentLocationsTool.Request("CUST-••••-0001"));
        assertThat(a.attributes()).isEqualTo(b.attributes());

        Set<CompareRecentLocationsTool.AnomalyLevel> levels = new HashSet<>();
        for (int i = 1; i <= 40; i++) {
            String ref = String.format("CUST-••••-%04d", i);
            levels.add(locationTool.execute(
                    new CompareRecentLocationsTool.Request(ref)).anomalyLevel());
        }
        assertThat(levels.size()).isGreaterThan(1);
    }

    // ---------- getMerchantRiskProfile ----------

    @Test
    void merchantRiskValidRequest() {
        var result = merchantTool.execute(
                new MerchantRiskProfileTool.Request("NORTHSTAR DIGITAL ••••73"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.MERCHANT_TOOL);
        assertThat(result.riskBand()).isNotNull();
        assertThat(result.category()).isNotBlank();
    }

    @Test
    void merchantRiskInvalidRequest() {
        assertThat(merchantTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(merchantTool.execute(
                new MerchantRiskProfileTool.Request("MERCHANT 4111111111111111"))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
    }

    @Test
    void merchantRiskDeterministic() {
        var a = merchantTool.execute(new MerchantRiskProfileTool.Request("NORTHSTAR DIGITAL ••••73"));
        var b = merchantTool.execute(new MerchantRiskProfileTool.Request("NORTHSTAR DIGITAL ••••73"));
        assertThat(a.attributes()).isEqualTo(b.attributes());
        assertThat(a.toolResultId()).isEqualTo(b.toolResultId());
    }

    // ---------- getPriorDisputes ----------

    @Test
    void priorDisputesValidRequest() {
        var result = priorDisputesTool.execute(
                new PriorDisputesTool.Request("CUST-••••-4821"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.PRIOR_DISPUTE_TOOL);
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.similarCount()).isEqualTo(0);
        assertThat(result.lookbackDays()).isEqualTo(90);
    }

    @Test
    void priorDisputesDeterministic() {
        var a = priorDisputesTool.execute(new PriorDisputesTool.Request("CUST-••••-0001"));
        var b = priorDisputesTool.execute(new PriorDisputesTool.Request("CUST-••••-0001"));
        assertThat(a.attributes()).isEqualTo(b.attributes());
    }

    @Test
    void priorDisputesInvalidRequest() {
        assertThat(priorDisputesTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
    }

    // ---------- calculateCaseSla ----------

    @Test
    void slaValidRequest() {
        OffsetDateTime base = OffsetDateTime.parse("2026-11-11T09:15:00Z");
        var result = slaTool.execute(new CaseSlaTool.Request(
                RecommendedRoute.FRAUD_INVESTIGATION, RiskLevel.HIGH, base));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.origin()).isEqualTo(EvidenceProvenance.SLA_TOOL);
        assertThat(result.queue()).isEqualTo("fraud-investigation-queue");
        assertThat(result.priority()).isEqualTo(CaseSlaTool.SlaPriority.P1);
        assertThat(result.targetAt()).isEqualTo(base.plusHours(4));
        assertThat(result.explanation()).contains("Synthetic training SLA");
    }

    @Test
    void slaDeterministicForSameBase() {
        OffsetDateTime base = OffsetDateTime.parse("2026-11-11T09:15:00Z");
        var a = slaTool.execute(new CaseSlaTool.Request(
                RecommendedRoute.MANUAL_REVIEW, RiskLevel.LOW, base));
        var b = slaTool.execute(new CaseSlaTool.Request(
                RecommendedRoute.MANUAL_REVIEW, RiskLevel.LOW, base));
        assertThat(a.targetAt()).isEqualTo(b.targetAt());
        assertThat(a.attributes()).isEqualTo(b.attributes());
    }

    @Test
    void slaInvalidRequest() {
        assertThat(slaTool.execute(null).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(slaTool.execute(new CaseSlaTool.Request(null, RiskLevel.LOW,
                OffsetDateTime.now())).status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(slaTool.execute(new CaseSlaTool.Request(
                RecommendedRoute.MANUAL_REVIEW, null, OffsetDateTime.now()))
                .status()).isEqualTo(ToolStatus.INVALID_REQUEST);
    }

    // ---------- cross-tool: failure produces no evidence ----------

    @Test
    void failedResultsProduceNoEvidenceItems() {
        assertThat(transactionTool.execute(null).toEvidence("FC-X")).isEmpty();
        assertThat(deviceTool.execute(null).toEvidence("FC-X")).isEmpty();
        assertThat(locationTool.execute(null).toEvidence("FC-X")).isEmpty();
        assertThat(merchantTool.execute(null).toEvidence("FC-X")).isEmpty();
        assertThat(priorDisputesTool.execute(null).toEvidence("FC-X")).isEmpty();
        assertThat(slaTool.execute(null).toEvidence("FC-X")).isEmpty();
    }

    @Test
    void successfulResultsProduceTraceableEvidence() {
        var result = transactionTool.execute(
                new TransactionDetailsTool.Request("TXN-2026-784102"));
        var items = result.toEvidence("FC-TEST0001");
        assertThat(items).hasSize(1);
        var item = items.get(0);
        assertThat(item.evidenceId()).startsWith("EVI-");
        assertThat(item.caseId()).isEqualTo("FC-TEST0001");
        assertThat(item.provenance()).isEqualTo(EvidenceProvenance.TRANSACTION_TOOL);
        assertThat(item.observedAt()).isNotNull();
    }

    @Test
    void determinismUtilityIsStable() {
        long h1 = Determinism.stableHash("seed", "key");
        long h2 = Determinism.stableHash("seed", "key");
        assertThat(h1).isEqualTo(h2);
        assertThat(Determinism.stableHash("seed", "other")).isNotEqualTo(h1);
        assertThat(Determinism.hashHex("a")).hasSize(64);
    }
}
