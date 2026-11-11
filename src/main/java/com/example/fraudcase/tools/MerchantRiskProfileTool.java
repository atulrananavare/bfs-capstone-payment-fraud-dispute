package com.example.fraudcase.tools;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.ToolStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * getMerchantRiskProfile — synthetic merchant characteristics. No real
 * blocklists or merchant systems are connected; all values are deterministic
 * synthetic training data.
 *
 * <p>Deterministic input source: {@code maskedMerchant} (+ seed).
 */
@Component
public class MerchantRiskProfileTool extends DeterministicToolSupport
        implements EvidenceTool<MerchantRiskProfileTool.Request, MerchantRiskProfileTool.Result> {

    public static final String NAME = "getMerchantRiskProfile";

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9 •*xX_.&',:\\-]{2,80}");
    private static final MerchantRiskBand[] BANDS = MerchantRiskBand.values();
    private static final String[] CATEGORIES = {
            "DIGITAL_GOODS", "TRAVEL", "GROCERY", "FUEL", "GAMING", "RETAIL"};

    public MerchantRiskProfileTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.MERCHANT_TOOL;
    }

    @Override
    public Result execute(Request request) {
        String merchant = request == null ? null : request.maskedMerchant();
        String resultId = resultId(NAME, String.valueOf(merchant));
        if (merchant == null || merchant.isBlank() || !SAFE_VALUE.matcher(merchant).matches()
                || !merchant.matches(".*(?:\\u2022|[*xX]{2,}).*")
                || merchant.replaceAll("[\\s-]", "").matches(".*\\d{9,}.*")) {
            return Result.failure(resultId, now(), ToolStatus.INVALID_REQUEST);
        }

        // ---- Pinned synthetic training fixtures (documented) ----
        if ("NORTHSTAR DIGITAL ••••73".equals(merchant)) {
            return new Result(resultId, now(), ToolStatus.SUCCESS,
                    MerchantRiskBand.MEDIUM, false, "DIGITAL_GOODS",
                    List.of("synthetic merchant in an elevated-risk category",
                            "no synthetic blocklist match"));
        }
        if ("DUPLICO RETAIL ••••11".equals(merchant)) {
            return new Result(resultId, now(), ToolStatus.SUCCESS,
                    MerchantRiskBand.LOW, false, "RETAIL",
                    List.of("synthetic merchant, normal profile"));
        }

        // Hash-derived synthetic outcome.
        MerchantRiskBand band = BANDS[Determinism.bucket(hash(NAME, "band", merchant), BANDS.length)];
        boolean blocklist = Determinism.bucket(hash(NAME, "bl", merchant), 10) == 0;
        String category = CATEGORIES[Determinism.bucket(hash(NAME, "cat", merchant), CATEGORIES.length)];
        List<String> observations = blocklist
                ? List.of("synthetic blocklist match", "elevated synthetic risk indicators")
                : List.of("synthetic merchant, " + band.name().toLowerCase() + " risk band");
        return new Result(resultId, now(), ToolStatus.SUCCESS,
                band, blocklist, category, observations);
    }

    public record Request(String maskedMerchant) implements ToolRequest {
        @Override
        public String canonical() {
            return "maskedMerchant=" + maskedMerchant;
        }
    }

    public enum MerchantRiskBand {
        LOW, MEDIUM, HIGH
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            MerchantRiskBand riskBand,
            Boolean blocklistMatch,
            String category,
            List<String> observations) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status,
                    null, null, null, List.of());
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.MERCHANT_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS riskBand=" + riskBand + " blocklistMatch=" + blocklistMatch;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of();
            }
            return Map.of(
                    "riskBand", String.valueOf(riskBand),
                    "blocklistMatch", String.valueOf(blocklistMatch),
                    "category", String.valueOf(category),
                    "observations", String.join("; ", observations));
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS) {
                return List.of();
            }
            EvidenceSeverity severity = Boolean.TRUE.equals(blocklistMatch) || riskBand == MerchantRiskBand.HIGH
                    ? EvidenceSeverity.HIGH
                    : riskBand == MerchantRiskBand.MEDIUM ? EvidenceSeverity.MEDIUM
                    : EvidenceSeverity.LOW;
            String summary = "riskBand=" + riskBand + ", blocklistMatch=" + blocklistMatch
                    + ", category=" + category;
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.MERCHANT, severity, summary,
                    EvidenceProvenance.MERCHANT_TOOL, observedAt));
        }
    }
}
