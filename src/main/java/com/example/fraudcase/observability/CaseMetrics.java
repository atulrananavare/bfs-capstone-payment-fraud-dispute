package com.example.fraudcase.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Application workflow metrics (Phase 9). Thin wrapper over Micrometer
 * that keeps metric names and tag values consistent and bounded.
 *
 * <p>Tag cardinality is deliberately tiny: {@code mode} ∈ processor modes,
 * {@code tool} ∈ the six allow-listed tools, {@code disposition} ∈ the five
 * review dispositions, {@code reason}/{@code source}/{@code operation} ∈
 * small fixed sets. Case ids, customer references, transaction references,
 * prompt text and user-entered text are never used as tag values.
 */
@Component
public class CaseMetrics {

    private final MeterRegistry registry;

    public CaseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    // ---- case intake ----

    public void caseCreated() {
        counter("fraudcase.cases.created").increment();
    }

    // ---- investigation lifecycle ----

    public void investigationStarted() {
        counter("fraudcase.investigations.started").increment();
    }

    /** @param mode RULES | SPRING_AI | RULES_FALLBACK */
    public void investigationCompleted(String mode) {
        counter("fraudcase.investigations.completed", "mode", mode).increment();
    }

    /** @param reason bounded: not_permitted | validation | unexpected */
    public void investigationFailed(String reason) {
        counter("fraudcase.investigations.failed", "reason", reason).increment();
    }

    public void recordInvestigationDuration(Timer.Sample sample, String mode) {
        sample.stop(timer("fraudcase.investigation.duration", "mode", mode));
    }

    // ---- tool execution ----

    /** @param tool one of the six allow-listed tool names */
    public void toolInvoked(String tool) {
        counter("fraudcase.tools.invocations", "tool", tool).increment();
    }

    /** @param tool one of the six allow-listed tool names */
    public void toolFailed(String tool) {
        counter("fraudcase.tools.failures", "tool", tool).increment();
    }

    public void recordToolDuration(Timer.Sample sample, String tool) {
        sample.stop(timer("fraudcase.tools.duration", "tool", tool));
    }

    // ---- AI/model calls ----

    /** @param operation bounded: investigation | followup */
    public void aiRequest(String operation) {
        counter("fraudcase.ai.requests", "operation", operation).increment();
    }

    /** @param operation bounded: investigation | followup */
    public void aiFailure(String operation) {
        counter("fraudcase.ai.failures", "operation", operation).increment();
    }

    public void recordAiDuration(Timer.Sample sample, String operation) {
        sample.stop(timer("fraudcase.ai.latency", "operation", operation));
    }

    // ---- human review ----

    /** @param disposition one of the five ReviewDispositionType values */
    public void reviewSubmitted(String disposition) {
        counter("fraudcase.reviews.submitted", "disposition", disposition).increment();
    }

    /** @param reason bounded: forbidden | stale | not_permitted |
     *                      validation | conflict | not_found */
    public void reviewRejected(String reason) {
        counter("fraudcase.reviews.rejected", "reason", reason).increment();
    }

    // ---- conversation ----

    /** @param source bounded: model | safe_fallback */
    public void messageAnswered(String source) {
        counter("fraudcase.messages.answered", "source", source).increment();
    }
}
