package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.ToolStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Operational trace of one tool execution. {@code requestHash} is a SHA-256
 * over the canonical request (already-masked values only);
 * {@code resultSummary} is a short redacted summary. Raw payloads and
 * sensitive values are never stored.
 */
@Entity
@Table(name = "tool_trace")
public class ToolTraceEntity {

    @Id
    @Column(name = "trace_id", length = 40)
    private String traceId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "investigation_id", length = 40)
    private String investigationId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ToolStatus status;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    protected ToolTraceEntity() {
    }

    public ToolTraceEntity(String traceId, String caseId, String investigationId,
                           String toolName, String requestHash, ToolStatus status,
                           long durationMs, String resultSummary, OffsetDateTime executedAt) {
        this.traceId = traceId;
        this.caseId = caseId;
        this.investigationId = investigationId;
        this.toolName = toolName;
        this.requestHash = requestHash;
        this.status = status;
        this.durationMs = durationMs;
        this.resultSummary = resultSummary;
        this.executedAt = executedAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public ToolStatus getStatus() {
        return status;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public OffsetDateTime getExecutedAt() {
        return executedAt;
    }
}
