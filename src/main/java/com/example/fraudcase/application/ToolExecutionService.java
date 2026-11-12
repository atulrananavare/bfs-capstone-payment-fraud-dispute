package com.example.fraudcase.application;

import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.ToolOutcome;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.observability.CaseMetrics;
import com.example.fraudcase.persistence.ToolTraceEntity;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.tools.Determinism;
import com.example.fraudcase.tools.EvidenceTool;
import com.example.fraudcase.tools.ToolRequest;
import com.example.fraudcase.tools.ToolResult;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executes allow-listed evidence tools and always produces a persisted,
 * redacted {@link ToolTraceEntity} plus an audit event.
 *
 * <p>Failure is safe: an unexpected tool exception yields a structured
 * {@code UNAVAILABLE} outcome and a {@code TOOL_FAILED} audit — never
 * invented evidence and never a leaked stack trace to callers.
 */
@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);
    private static final int SUMMARY_MAX = 500;

    private final ToolTraceRepository traceRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final CaseMetrics metrics;

    public ToolExecutionService(ToolTraceRepository traceRepository,
                                AuditService auditService, Clock clock,
                                CaseMetrics metrics) {
        this.traceRepository = traceRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Result of one execution: the neutral {@link ToolOutcome} handed to
     * processors plus the trace id and the raw typed result (for evidence
     * mapping by the orchestrator).
     */
    public record Execution(ToolOutcome outcome, String traceId, ToolResult result) {
    }

    public <RQ extends ToolRequest, RS extends ToolResult> Execution run(
            EvidenceTool<RQ, RS> tool, RQ request, String caseId, String investigationId) {
        long started = System.nanoTime();
        Timer.Sample sample = metrics.start();
        metrics.toolInvoked(tool.name());
        RS result = null;
        ToolStatus status;
        try {
            result = tool.execute(request);
            status = result.status();
        } catch (Exception ex) {
            status = ToolStatus.UNAVAILABLE;
            log.warn("Tool {} failed for case {} investigation {}: {}",
                    tool.name(), caseId, investigationId, ex.getMessage());
        }
        metrics.recordToolDuration(sample, tool.name());
        if (status != ToolStatus.SUCCESS && status != ToolStatus.NOT_FOUND) {
            metrics.toolFailed(tool.name());
            log.warn("TOOL_FAILED caseId={} tool={} status={}", caseId, tool.name(), status);
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        String requestHash = Determinism.hashHex(tool.name(),
                request == null ? "null" : request.canonical());
        String traceId = "TRC-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        String summary = result == null ? "execution failed" : result.traceSummary();
        if (summary.length() > SUMMARY_MAX) {
            summary = summary.substring(0, SUMMARY_MAX);
        }

        traceRepository.save(new ToolTraceEntity(
                traceId, caseId, investigationId, tool.name(), requestHash,
                status, durationMs, summary, OffsetDateTime.now(clock)));

        boolean ran = status == ToolStatus.SUCCESS || status == ToolStatus.NOT_FOUND;
        auditService.record(caseId,
                ran ? AuditEventType.TOOL_EXECUTED : AuditEventType.TOOL_FAILED,
                ActorType.SYSTEM, tool.name() + "|" + status + "|" + requestHash);

        ToolOutcome outcome = new ToolOutcome(
                tool.name(), tool.origin(), status,
                result == null ? null : result.toolResultId(),
                result == null ? OffsetDateTime.now(clock) : result.observedAt(),
                result == null ? Map.of() : result.attributes(),
                summary);
        return new Execution(outcome, traceId, result);
    }
}
