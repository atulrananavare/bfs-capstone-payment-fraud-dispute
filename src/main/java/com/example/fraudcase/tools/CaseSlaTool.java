package com.example.fraudcase.tools;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * calculateCaseSla — computes a TRAINING SLA target for a proposed route.
 * This is a synthetic training value, never a production bank SLA.
 *
 * <p>Deterministic input source: (route, riskLevel, baseTimestamp). The
 * caller supplies the case/report timestamp as the baseline so results are
 * reproducible and never depend on wall-clock execution time.
 */
@Component
public class CaseSlaTool extends DeterministicToolSupport
        implements EvidenceTool<CaseSlaTool.Request, CaseSlaTool.Result> {

    public static final String NAME = "calculateCaseSla";

    private static final Duration P1_TARGET = Duration.ofHours(4);
    private static final Duration P2_TARGET = Duration.ofHours(24);
    private static final Duration P3_TARGET = Duration.ofHours(72);

    public CaseSlaTool(CapstoneProperties properties, Clock clock) {
        super(properties, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EvidenceProvenance origin() {
        return EvidenceProvenance.SLA_TOOL;
    }

    @Override
    public Result execute(Request request) {
        String resultId = resultId(NAME,
                String.valueOf(request == null ? null : request.route()),
                String.valueOf(request == null ? null : request.riskLevel()),
                String.valueOf(request == null ? null : request.baseTimestamp()));
        if (request == null || request.route() == null || request.riskLevel() == null
                || request.baseTimestamp() == null) {
            return Result.failure(resultId, now(), ToolStatus.INVALID_REQUEST);
        }

        String queue = switch (request.route()) {
            case FRAUD_INVESTIGATION -> "fraud-investigation-queue";
            case DISPUTE_OPERATIONS -> "dispute-operations-queue";
            case PAYMENT_OPERATIONS -> "payment-operations-queue";
            case MANUAL_REVIEW -> "manual-review-queue";
        };
        SlaPriority priority = switch (request.riskLevel()) {
            case HIGH -> SlaPriority.P1;
            case MEDIUM -> SlaPriority.P2;
            case LOW -> SlaPriority.P3;
        };
        Duration target = switch (priority) {
            case P1 -> P1_TARGET;
            case P2 -> P2_TARGET;
            case P3 -> P3_TARGET;
        };
        OffsetDateTime targetAt = request.baseTimestamp().plus(target);
        String explanation = "Synthetic training SLA: route " + request.route()
                + " at priority " + priority + " targets " + target.toHours()
                + "h from the case report time.";
        return new Result(resultId, now(), ToolStatus.SUCCESS,
                queue, priority, targetAt, explanation);
    }

    public record Request(
            RecommendedRoute route,
            RiskLevel riskLevel,
            OffsetDateTime baseTimestamp) implements ToolRequest {

        @Override
        public String canonical() {
            return "route=" + route + "|risk=" + riskLevel + "|base=" + baseTimestamp;
        }
    }

    public enum SlaPriority {
        P1, P2, P3
    }

    public record Result(
            String toolResultId,
            OffsetDateTime observedAt,
            ToolStatus status,
            String queue,
            SlaPriority priority,
            OffsetDateTime targetAt,
            String explanation) implements ToolResult {

        static Result failure(String toolResultId, OffsetDateTime observedAt, ToolStatus status) {
            return new Result(toolResultId, observedAt, status,
                    null, null, null, null);
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.SLA_TOOL;
        }

        @Override
        public String traceSummary() {
            if (status != ToolStatus.SUCCESS) {
                return "status=" + status;
            }
            return "status=SUCCESS queue=" + queue + " priority=" + priority + " targetAt=" + targetAt;
        }

        @Override
        public Map<String, String> attributes() {
            if (status != ToolStatus.SUCCESS) {
                return Map.of();
            }
            return Map.of(
                    "queue", queue,
                    "priority", String.valueOf(priority),
                    "targetAt", String.valueOf(targetAt),
                    "explanation", explanation);
        }

        public List<EvidenceItem> toEvidence(String caseId) {
            if (status != ToolStatus.SUCCESS) {
                return List.of();
            }
            String summary = "queue=" + queue + ", priority=" + priority + ", targetAt=" + targetAt;
            return List.of(new EvidenceItem(
                    Determinism.shortId("EVI", 16, caseId, NAME, summary),
                    caseId, EvidenceType.SLA, EvidenceSeverity.INFO, summary,
                    EvidenceProvenance.SLA_TOOL, observedAt));
        }
    }
}
