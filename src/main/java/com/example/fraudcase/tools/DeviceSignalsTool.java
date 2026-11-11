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
 * getDeviceSignals — synthetic device novelty/trust indicators.
 *
 * <p>Deterministic input source: {@code maskedCustomerRef} (+ seed).
 */
@Component
public class DeviceSignalsTool extends DeterministicToolSupport
        implements EvidenceTool<DeviceSignalsTool.Request, DeviceSignalsTool.Result> {

    public static final String NAME = "getDeviceSignals";

    private static final Pattern MASKED_REF = Pattern.compile("[A-Za-z0-9 •*xX_.:\\-]{2,64}");
    private static final OffsetDateTime SYNTHETIC_EPOCH = OffsetDateTime.parse("2024-01-01T00:00:00Z");

    public DeviceSignalsTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.DEVICE_TOOL;
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
            // Standard training sample: newly observed device.
            return new Result(resultId, now(), ToolStatus.SUCCESS,
                    false, OffsetDateTime.parse("2026-11-06T08:40:00+05:30"),
                    DeviceTrustLevel.NEW_DEVICE,
                    List.of("device first observed at transaction time",
                            "no prior device trust history"));
        }

        // Hash-derived synthetic outcome.
        boolean known = Determinism.bucket(hash(NAME, "known", ref), 4) != 0;
        DeviceTrustLevel trust;
        if (known) {
            trust = Determinism.bucket(hash(NAME, "tl", ref), 2) == 0
                    ? DeviceTrustLevel.TRUSTED : DeviceTrustLevel.ESTABLISHED;
        } else {
            trust = Determinism.bucket(hash(NAME, "tl", ref), 2) == 0
                    ? DeviceTrustLevel.NEW_DEVICE : DeviceTrustLevel.UNTRUSTED;
        }
        OffsetDateTime firstSeen = SYNTHETIC_EPOCH
                .plusDays(Determinism.bucket(hash(NAME, "fs", ref), 900));
        List<String> observations = known
                ? List.of("device has established history with this customer")
                : List.of("device not previously associated with this customer");
        return new Result(resultId, now(), ToolStatus.SUCCESS,
                known, firstSeen, trust, observations);
    }

    public record Request(String maskedCustomerRef) implements ToolRequest {
        @Override
        public String canonical() {
            return "maskedCustomerRef=" + maskedCustomerRef;
        }
    }

    public enum DeviceTrustLevel {
        TRUSTED, ESTABLISHED, NEW_DEVICE, UNTRUSTED
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            Boolean deviceKnown,
            OffsetDateTime firstSeenAt,
            DeviceTrustLevel trustLevel,
            List<String> observations) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status,
                    null, null, null, List.of());
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.DEVICE_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS deviceKnown=" + deviceKnown + " trustLevel=" + trustLevel;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of();
            }
            return Map.of(
                    "deviceKnown", String.valueOf(deviceKnown),
                    "firstSeenAt", String.valueOf(firstSeenAt),
                    "trustLevel", String.valueOf(trustLevel),
                    "observations", String.join("; ", observations));
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS) {
                return List.of();
            }
            EvidenceSeverity severity = Boolean.FALSE.equals(deviceKnown)
                    ? EvidenceSeverity.HIGH
                    : trustLevel == DeviceTrustLevel.UNTRUSTED ? EvidenceSeverity.HIGH
                    : trustLevel == DeviceTrustLevel.ESTABLISHED ? EvidenceSeverity.LOW
                    : EvidenceSeverity.INFO;
            String summary = "deviceKnown=" + deviceKnown + ", trustLevel=" + trustLevel
                    + ", firstSeenAt=" + firstSeenAt;
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.DEVICE, severity, summary,
                    EvidenceProvenance.DEVICE_TOOL, observedAt));
        }
    }
}
