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
 * compareRecentLocations — compares synthetic current activity with recent
 * trusted locations. Never infers real customer locations; all geography is
 * synthetic training data.
 *
 * <p>Deterministic input source: {@code maskedCustomerRef} (+ seed).
 */
@Component
public class CompareRecentLocationsTool extends DeterministicToolSupport
        implements EvidenceTool<CompareRecentLocationsTool.Request, CompareRecentLocationsTool.Result> {

    public static final String NAME = "compareRecentLocations";

    private static final Pattern MASKED_REF = Pattern.compile("[A-Za-z0-9 •*xX_.:\\-]{2,64}");

    public CompareRecentLocationsTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.LOCATION_TOOL;
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
            // Standard training sample: activity inconsistent with recent
            // trusted locations.
            return new Result(resultId, now(), ToolStatus.SUCCESS,
                    1450, 130L, AnomalyLevel.HIGH,
                    List.of("activity inconsistent with recent trusted locations",
                            "distance is implausibly large for the elapsed time"));
        }

        // Hash-derived synthetic outcome.
        int distanceKm = Determinism.bucket(hash(NAME, "km", ref), 3000);
        Long timeDeltaMinutes = (long) Determinism.bucket(hash(NAME, "td", ref), 2880);
        AnomalyLevel anomaly = distanceKm < 50 ? AnomalyLevel.LOW
                : distanceKm < 800 ? AnomalyLevel.MEDIUM : AnomalyLevel.HIGH;
        List<String> observations = switch (anomaly) {
            case LOW -> List.of("activity consistent with recent trusted locations");
            case MEDIUM -> List.of("moderate deviation from recent trusted locations");
            case HIGH -> List.of("activity inconsistent with recent trusted locations",
                    "distance is implausibly large for the elapsed time");
            default -> List.of("location evidence unavailable");
        };
        return new Result(resultId, now(), ToolStatus.SUCCESS,
                distanceKm, timeDeltaMinutes, anomaly, observations);
    }

    public record Request(String maskedCustomerRef) implements ToolRequest {
        @Override
        public String canonical() {
            return "maskedCustomerRef=" + maskedCustomerRef;
        }
    }

    public enum AnomalyLevel {
        LOW, MEDIUM, HIGH, UNAVAILABLE
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            Integer distanceKm,
            Long timeDeltaMinutes,
            AnomalyLevel anomalyLevel,
            List<String> observations) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status,
                    null, null, null, List.of());
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.LOCATION_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS distanceKm=" + distanceKm + " anomalyLevel=" + anomalyLevel;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of();
            }
            return Map.of(
                    "distanceKm", String.valueOf(distanceKm),
                    "timeDeltaMinutes", String.valueOf(timeDeltaMinutes),
                    "anomalyLevel", String.valueOf(anomalyLevel),
                    "observations", String.join("; ", observations));
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS) {
                return List.of();
            }
            EvidenceSeverity severity = switch (anomalyLevel) {
                case HIGH -> EvidenceSeverity.HIGH;
                case MEDIUM -> EvidenceSeverity.MEDIUM;
                case LOW -> EvidenceSeverity.LOW;
                default -> EvidenceSeverity.INFO;
            };
            String summary = "distanceKm=" + distanceKm
                    + ", timeDeltaMinutes=" + timeDeltaMinutes
                    + ", anomalyLevel=" + anomalyLevel;
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.LOCATION, severity, summary,
                    EvidenceProvenance.LOCATION_TOOL, observedAt));
        }
    }
}
