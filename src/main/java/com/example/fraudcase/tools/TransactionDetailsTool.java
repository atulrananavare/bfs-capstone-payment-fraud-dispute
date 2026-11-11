package com.example.fraudcase.tools;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * getTransactionDetails — synthetic transaction state, channel and
 * authentication fields.
 *
 * <p>Deterministic input source: {@code transactionRef} (+ configured seed).
 * Pinned training fixtures below document well-known synthetic cases;
 * all other references derive outcomes from the stable hash.
 */
@Component
public class TransactionDetailsTool extends DeterministicToolSupport
        implements EvidenceTool<TransactionDetailsTool.Request, TransactionDetailsTool.Result> {

    public static final String NAME = "getTransactionDetails";

    private static final Pattern SYNTHETIC_REF = Pattern.compile("^[A-Z]{2,8}-[A-Z0-9-]{2,32}$");
    private static final TransactionStatus[] STATUSES = {
            TransactionStatus.POSTED, TransactionStatus.SETTLED, TransactionStatus.PENDING};
    private static final TransactionChannel[] CHANNELS = {
            TransactionChannel.CARD_PRESENT, TransactionChannel.CARD_NOT_PRESENT, TransactionChannel.ATM};
    private static final AuthOutcome[] AUTH_OUTCOMES = {
            AuthOutcome.SUCCESS, AuthOutcome.SUCCESS, AuthOutcome.STEP_UP,
            AuthOutcome.UNAVAILABLE, AuthOutcome.FAILED};

    public TransactionDetailsTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.TRANSACTION_TOOL;
    }

    @Override
    public Result execute(Request request) {
        String ref = request == null ? null : request.transactionRef();
        String resultId = resultId(NAME, String.valueOf(ref));
        if (ref == null || ref.isBlank() || !SYNTHETIC_REF.matcher(ref).matches()) {
            return Result.failure(resultId, now(), ToolStatus.INVALID_REQUEST);
        }

        // ---- Pinned synthetic training fixtures (documented) ----
        switch (ref) {
            case "TXN-2026-784102" -> {
                // Standard training sample: posted CNP purchase, card in
                // possession, authentication outcome UNAVAILABLE (missing,
                // never invented).
                return new Result(resultId, now(), ToolStatus.SUCCESS, true,
                        TransactionStatus.POSTED,
                        OffsetDateTime.parse("2026-11-06T08:42:00+05:30"),
                        new BigDecimal("48750.00"),
                        TransactionChannel.CARD_NOT_PRESENT,
                        AuthOutcome.UNAVAILABLE,
                        "NORTHSTAR DIGITAL ••••73", false);
            }
            case "TXN-4040-404040" -> {
                // Training case: transaction cannot be found.
                return Result.failure(resultId, now(), ToolStatus.NOT_FOUND);
            }
            case "TXN-5555-000002" -> {
                // Training case: confirmed duplicate posting fingerprint.
                return new Result(resultId, now(), ToolStatus.SUCCESS, true,
                        TransactionStatus.POSTED,
                        OffsetDateTime.parse("2026-10-20T10:00:00Z"),
                        new BigDecimal("1200.00"),
                        TransactionChannel.CARD_NOT_PRESENT,
                        AuthOutcome.SUCCESS,
                        "DUPLICO RETAIL ••••11", true);
            }
            default -> {
                // Hash-derived synthetic outcome.
                long h = hash(NAME, "found", ref);
                if (Determinism.bucket(h, 10) == 0) {
                    return Result.failure(resultId, now(), ToolStatus.NOT_FOUND);
                }
                TransactionStatus status = STATUSES[Determinism.bucket(hash(NAME, "st", ref), 3)];
                TransactionChannel channel = CHANNELS[Determinism.bucket(hash(NAME, "ch", ref), 3)];
                AuthOutcome auth = AUTH_OUTCOMES[Determinism.bucket(hash(NAME, "auth", ref), 5)];
                BigDecimal amount = BigDecimal.valueOf(Determinism.bucket(hash(NAME, "amt", ref), 90000) + 100L)
                        .movePointLeft(2);
                String merchant = "SYNTH MERCHANT ••••" + String.format("%02d",
                        Determinism.bucket(hash(NAME, "mer", ref), 90) + 10);
                boolean duplicate = Determinism.bucket(hash(NAME, "dup", ref), 8) == 0;
                OffsetDateTime occurred = OffsetDateTime.parse("2026-01-01T00:00:00Z")
                        .plusDays(Determinism.bucket(hash(NAME, "ts", ref), 300));
                return new Result(resultId, now(), ToolStatus.SUCCESS, true,
                        status, occurred, amount, channel, auth, merchant, duplicate);
            }
        }
    }

    public record Request(String transactionRef) implements ToolRequest {
        @Override
        public String canonical() {
            return "transactionRef=" + transactionRef;
        }
    }

    public enum AuthOutcome {
        SUCCESS, STEP_UP, FAILED, UNAVAILABLE, NOT_APPLICABLE
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            boolean found,
            TransactionStatus transactionStatus,
            OffsetDateTime transactionTimestamp,
            BigDecimal amount,
            TransactionChannel channel,
            AuthOutcome authOutcome,
            String maskedMerchant,
            boolean duplicateFingerprintMatch) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status, false,
                    null, null, null, null, null, null, false);
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.TRANSACTION_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS found=" + found
                    + " txStatus=" + transactionStatus
                    + " channel=" + channel
                    + " authOutcome=" + authOutcome
                    + " duplicateFingerprint=" + duplicateFingerprintMatch;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of("found", String.valueOf(found));
            }
            return Map.of(
                    "found", String.valueOf(found),
                    "transactionStatus", String.valueOf(transactionStatus),
                    "occurredAt", String.valueOf(transactionTimestamp),
                    "amount", String.valueOf(amount),
                    "channel", String.valueOf(channel),
                    "authOutcome", String.valueOf(authOutcome),
                    "maskedMerchant", String.valueOf(maskedMerchant),
                    "duplicateFingerprintMatch", String.valueOf(duplicateFingerprintMatch));
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS || !found) {
                return List.of();
            }
            EvidenceSeverity severity = authOutcome == AuthOutcome.FAILED
                    ? EvidenceSeverity.HIGH
                    : duplicateFingerprintMatch ? EvidenceSeverity.MEDIUM : EvidenceSeverity.INFO;
            String summary = "transaction " + transactionStatus + " via " + channel
                    + ", authOutcome=" + authOutcome
                    + (duplicateFingerprintMatch ? ", duplicate fingerprint confirmed" : "");
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.TRANSACTION, severity, summary,
                    EvidenceProvenance.TRANSACTION_TOOL, observedAt));
        }
    }
}
