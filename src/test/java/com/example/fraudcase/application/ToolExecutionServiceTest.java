package com.example.fraudcase.application;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.persistence.ToolTraceEntity;
import com.example.fraudcase.persistence.ToolTraceRepository;
import com.example.fraudcase.tools.EvidenceTool;
import com.example.fraudcase.tools.ToolRequest;
import com.example.fraudcase.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the safe failure contract of {@link ToolExecutionService}: every
 * execution produces a persisted redacted trace and an audit event, and an
 * unexpected tool exception never propagates to callers.
 */
class ToolExecutionServiceTest {

    private ToolTraceRepository traces;
    private AuditService audit;
    private ToolExecutionService service;

    record Req(String value) implements ToolRequest {
        @Override
        public String canonical() {
            return "value=" + value;
        }
    }

    record Res(OffsetDateTime observedAt, ToolStatus status) implements ToolResult {
        @Override
        public String toolResultId() {
            return "RES-1";
        }

        @Override
        public EvidenceProvenance origin() {
            return EvidenceProvenance.DEVICE_TOOL;
        }

        @Override
        public String traceSummary() {
            return "status=" + status;
        }

        @Override
        public Map<String, String> attributes() {
            return Map.of();
        }

        @Override
        public List<EvidenceItem> toEvidence(String caseId) {
            return List.of();
        }
    }

    private EvidenceTool<Req, Res> tool;
    private Req request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        traces = mock(ToolTraceRepository.class);
        audit = mock(AuditService.class);
        service = new ToolExecutionService(traces, audit, Clock.systemUTC(), TestFixtures.testMetrics());
        tool = mock(EvidenceTool.class);
        when(tool.name()).thenReturn("fakeTool");
        when(tool.origin()).thenReturn(EvidenceProvenance.DEVICE_TOOL);
        request = new Req("CUST-••••-4821");
    }

    private ToolTraceEntity capturedTrace() {
        ArgumentCaptor<ToolTraceEntity> captor = ArgumentCaptor.forClass(ToolTraceEntity.class);
        verify(traces).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void successfulExecutionPersistsTraceAndAudit() {
        when(tool.execute(request)).thenReturn(new Res(OffsetDateTime.now(), ToolStatus.SUCCESS));

        var execution = service.run(tool, request, "FC-1", "INV-1");

        ToolTraceEntity trace = capturedTrace();
        assertThat(trace.getStatus()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(trace.getRequestHash()).hasSize(64);
        assertThat(trace.getResultSummary()).isEqualTo("status=SUCCESS");
        assertThat(trace.getDurationMs()).isNotNegative();
        assertThat(trace.getTraceId()).startsWith("TRC-");
        assertThat(execution.outcome().status()).isEqualTo(ToolStatus.SUCCESS);
        verify(audit).record(eq("FC-1"), eq(AuditEventType.TOOL_EXECUTED),
                eq(com.example.fraudcase.domain.ActorType.SYSTEM), any());
    }

    @Test
    void notFoundIsASafeRanOutcome() {
        when(tool.execute(request)).thenReturn(new Res(OffsetDateTime.now(), ToolStatus.NOT_FOUND));

        var execution = service.run(tool, request, "FC-1", "INV-1");

        assertThat(capturedTrace().getStatus()).isEqualTo(ToolStatus.NOT_FOUND);
        verify(audit).record(eq("FC-1"), eq(AuditEventType.TOOL_EXECUTED),
                any(), any());
        assertThat(execution.outcome().status()).isEqualTo(ToolStatus.NOT_FOUND);
    }

    @Test
    void unexpectedToolExceptionBecomesUnavailableAndToolFailed() {
        when(tool.execute(request)).thenThrow(new RuntimeException("synthetic boom"));

        var execution = service.run(tool, request, "FC-1", "INV-1");

        // Never propagates; produces a structured UNAVAILABLE outcome.
        assertThat(execution.outcome().status()).isEqualTo(ToolStatus.UNAVAILABLE);
        ToolTraceEntity trace = capturedTrace();
        assertThat(trace.getStatus()).isEqualTo(ToolStatus.UNAVAILABLE);
        assertThat(trace.getResultSummary()).isEqualTo("execution failed");
        assertThat(trace.getResultSummary()).doesNotContain("boom");
        verify(audit).record(eq("FC-1"), eq(AuditEventType.TOOL_FAILED),
                eq(com.example.fraudcase.domain.ActorType.SYSTEM), any());
    }

    @Test
    void nullRequestStillProducesStructuredTrace() {
        when(tool.execute(null)).thenReturn(new Res(OffsetDateTime.now(), ToolStatus.INVALID_REQUEST));

        var execution = service.run(tool, null, "FC-1", "INV-1");

        assertThat(execution.outcome().status()).isEqualTo(ToolStatus.INVALID_REQUEST);
        assertThat(capturedTrace().getRequestHash()).hasSize(64);
        verify(audit).record(eq("FC-1"), eq(AuditEventType.TOOL_FAILED), any(), any());
        verify(traces, never()).delete(any());
    }
}
