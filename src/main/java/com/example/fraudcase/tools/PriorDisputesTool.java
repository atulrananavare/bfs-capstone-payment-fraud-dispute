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
 * getPriorDisputes — summarises earlier synthetic claims for the MASKED
 * customer reference. Never stores or returns real customer history.
 *
 * <p>Deterministic input source: {@code maskedCustomerRef} (+ seed).
 */
@Component
public class PriorDisputesTool extends DeterministicToolSupport
        implements EvidenceTool<PriorDisputesTool.Request, PriorDisputesTool.Result> {

    public static final String NAME = "getPriorDisputes";

    private static final Pattern MASKED_REF = Pattern.compile("[A-Za-z0-9 •*xX_.:\\-]{2,64}");
    private static final int LOOKBACK_DAYS = 90;

    public PriorDisputesTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.PRIOR_DISPUTE_TOOL;
    }

    @Override
    public Result execute(Request request) {
        String ref = request == null ? null : request.maskedCustomerRef();
        String resultId = resultId(NAME, String.valueOf(ref));
        if (ref == null || ref.isBlank() || !MASKED_REF.matcher(ref).matches()
                || !ref.matches(".*(?:\\u2022|[*xX]{2,}).*")
                || ref.replaceAll("[\\s-]", "").matches(".*\\d{9,}.*")) {
            return Result.failure(resultId, now(), ToolStatus.INVALID_REQUEST);
        }

        // ---- Pinned synthetic training fixtures (documented) ----
        if ("CUST-••••-4821".equals(ref)) {
            return new Result(resultId, now(), ToolStatus.SUCCESS,
                    1, 0, LOOKBACK_DAYS,
                    List.of("one prior unrelated synthetic dispute"));
        }

        // Hash-derived synthetic outcome.
        int count = Determinism.bucket(hash(NAME, "cnt", ref), 5);
        int similar = count == 0 ? 0
                : Math.min(count, Determinism.bucket(hash(NAME, "sim", ref), 3));
        List<String> observations = count == 0
                ? List.of("no prior disputes in lookback window")
                : List.of(count + " prior dispute(s), " + similar + " similar");
        return new Result(resultId, now(), ToolStatus.SUCCESS,
                count, similar, LOOKBACK_DAYS, observations);
    }

    public record Request(String maskedCustomerRef) implements ToolRequest {
        @Override
        public String canonical() {
            return "maskedCustomerRef=" + maskedCustomerRef;
        }
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            Integer count,
            Integer similarCount,
            Integer lookbackDays,
            List<String> observations) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status,
                    null, null, null, List.of());
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.PRIOR_DISPUTE_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS count=" + count + " similarCount=" + similarCount;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of();
            }
            return Map.of(
                    "count", String.valueOf(count),
                    "similarCount", String.valueOf(similarCount),
                    "lookbackDays", String.valueOf(lookbackDays),
                    "observations", String.join("; ", observations));
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS) {
                return List.of();
            }
            EvidenceSeverity severity = count >= 3 || similarCount >= 2
                    ? EvidenceSeverity.MEDIUM : EvidenceSeverity.INFO;
            String summary = "priorDisputes=" + count + ", similar=" + similarCount
                    + ", lookbackDays=" + lookbackDays;
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.PRIOR_DISPUTE, severity, summary,
                    EvidenceProvenance.PRIOR_DISPUTE_TOOL, observedAt));
        }
    }
}
