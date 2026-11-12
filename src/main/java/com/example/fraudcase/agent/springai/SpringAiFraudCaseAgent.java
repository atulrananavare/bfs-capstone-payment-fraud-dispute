package com.example.fraudcase.agent.springai;

import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyPassage;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.observability.CaseMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI implementation of {@link FraudCaseProcessor} (Phase 3).
 *
 * <p>Design guarantees:
 * <ul>
 *   <li>Trusted instructions live in the system message (resource +
 *       structured-output format). All case content is untrusted data in the
 *       user message — the model can never rewrite the safety boundary.</li>
 *   <li>Only the six allow-listed deterministic tools are registered, and
 *       every callback is bound to the current case
 *       ({@link SpringAiToolAdapter}). Tool calls run through the same
 *       {@code ToolExecutionService} as application-driven calls, so they
 *       produce real traces — never fabricated execution.</li>
 *   <li>The tool-call loop is owned here and bounded by
 *       {@code capstone.ai.max-rounds}; {@code internalToolExecutionEnabled}
 *       is disabled so no provider can loop outside this bound.</li>
 *   <li>Output is bound to {@link AiRecommendationOutput} and mapped to the
 *       framework-neutral {@link FraudCaseRecommendation}. Failures are
 *       reported as typed exceptions so the orchestrator can audit
 *       invocation failure vs. output rejection distinctly and fall back to
 *       the deterministic rules processor.</li>
 *   <li>Raw prompts, raw model text and provider error details are never
 *       logged or audited — only bounded identifiers and event types.</li>
 * </ul>
 */
@Component
public class SpringAiFraudCaseAgent implements FraudCaseProcessor {

    private static final Logger log = LoggerFactory.getLogger(SpringAiFraudCaseAgent.class);

    /**
     * Distinctive phrases from the system-prompt resource. A rationale that
     * contains one is regurgitating instructions (often a successful
     * prompt-leak attempt), so the output is rejected rather than surfaced.
     */
    private static final List<String> PROMPT_LEAK_MARKERS = List.of(
            "INVESTIGATION AND RECOMMENDATION ASSISTANT",
            "Non-negotiable rules");

    private final ObjectProvider<ChatModel> chatModel;
    private final ToolCallingManager toolCallingManager;
    private final SpringAiToolAdapter toolAdapter;
    private final AuditService auditService;
    private final CapstoneProperties properties;
    private final Resource systemPromptResource;
    private final CaseMetrics metrics;

    public SpringAiFraudCaseAgent(ObjectProvider<ChatModel> chatModel,
                                  ToolCallingManager toolCallingManager,
                                  SpringAiToolAdapter toolAdapter,
                                  AuditService auditService,
                                  CapstoneProperties properties,
                                  @Value("classpath:prompts/fraud-case-system-prompt.st")
                                  Resource systemPromptResource,
                                  CaseMetrics metrics) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolAdapter = toolAdapter;
        this.auditService = auditService;
        this.properties = properties;
        this.systemPromptResource = systemPromptResource;
        this.metrics = metrics;
    }

    @Override
    public FraudCaseRecommendation analyze(FraudCaseContext context) {
        Timer.Sample sample = metrics.start();
        metrics.aiRequest("investigation");
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            metrics.aiFailure("investigation");
            metrics.recordAiDuration(sample, "investigation");
            throw new SpringAiProcessingException(
                    "no ChatModel is configured for spring-ai mode");
        }
        String caseId = context.caseId();
        auditService.record(caseId, AuditEventType.MODEL_INVOCATION_STARTED,
                ActorType.SYSTEM, "spring-ai|tools=" + SpringAiToolAdapter.ALLOWED_TOOL_NAMES.size()
                        + "|maxToolCalls=" + properties.getAi().getMaxToolCalls());

        try {
            FraudCaseRecommendation recommendation = invoke(model, context);
            auditService.record(caseId, AuditEventType.MODEL_INVOCATION_COMPLETED,
                    ActorType.SYSTEM, recommendation.classification()
                            + "|" + recommendation.recommendedRoute());
            metrics.recordAiDuration(sample, "investigation");
            return recommendation;
        } catch (SpringAiProcessingException ex) {
            metrics.aiFailure("investigation");
            metrics.recordAiDuration(sample, "investigation");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.aiFailure("investigation");
            metrics.recordAiDuration(sample, "investigation");
            // Provider/network/SDK failures land here. Log the exception type
            // only — provider messages may embed request details.
            log.warn("AI_REQUEST_FAILED caseId={} type={}",
                    caseId, ex.getClass().getSimpleName());
            throw new SpringAiProcessingException(
                    "model invocation failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private FraudCaseRecommendation invoke(ChatModel model, FraudCaseContext context) {
        BeanOutputConverter<AiRecommendationOutput> converter =
                new BeanOutputConverter<>(AiRecommendationOutput.class);

        String systemText = SpringAiPromptBuilder.systemPrompt(
                systemPromptText(), converter.getFormat());
        String userText = SpringAiPromptBuilder.userPrompt(context);

        List<ToolCallback> callbacks = toolAdapter.callbacksFor(
                context.fraudCase(), context.investigationId());

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .temperature(properties.getAi().getTemperature())
                .build();

        List<Message> history = new ArrayList<>(List.of(
                new SystemMessage(systemText), new UserMessage(userText)));
        Prompt prompt = new Prompt(history, options);

        int maxRounds = properties.getAi().getMaxRounds();
        ChatResponse response = null;
        for (int round = 0; round < maxRounds; round++) {
            response = model.call(prompt);
            AssistantMessage output = response.getResult().getOutput();
            if (!output.hasToolCalls()) {
                break;
            }
            if (round == maxRounds - 1) {
                throw new SpringAiProcessingException(
                        "model did not finish within the tool-call round bound ("
                                + maxRounds + ")");
            }
            ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(executed.conversationHistory(), options);
        }

        String text = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new InvalidModelOutputException("model returned empty output");
        }

        AiRecommendationOutput output;
        try {
            output = converter.convert(text);
        } catch (RuntimeException ex) {
            throw new InvalidModelOutputException(
                    "model output is not valid structured JSON", ex);
        }
        if (output == null) {
            throw new InvalidModelOutputException("model output did not convert");
        }
        return toDomain(output, context);
    }

    private FraudCaseRecommendation toDomain(AiRecommendationOutput out,
                                             FraudCaseContext context) {
        if (out.caseId() != null && !out.caseId().isBlank()
                && !out.caseId().equals(context.caseId())) {
            // The model claimed a different case — its entire output is
            // untrusted, not silently re-scoped.
            throw new InvalidModelOutputException(
                    "model output caseId does not match the investigated case");
        }
        List<PolicyCitation> citations =
                resolveCitations(out.policyCitations(), context);
        if (out.rationale() != null && PROMPT_LEAK_MARKERS.stream()
                .anyMatch(out.rationale()::contains)) {
            // Parroting system-prompt material is not a rationale.
            throw new InvalidModelOutputException(
                    "rationale contains system-prompt material");
        }
        CaseClassification classification = parseEnum(
                CaseClassification.class, out.classification(), "classification");
        RiskLevel risk = parseEnum(RiskLevel.class, out.riskLevel(), "riskLevel");
        RecommendedRoute route = parseEnum(
                RecommendedRoute.class, out.recommendedRoute(), "recommendedRoute");
        double confidence = out.confidence() == null ? -1 : out.confidence();
        boolean humanReview = out.humanReviewRequired() != null
                && out.humanReviewRequired();

        try {
            return new FraudCaseRecommendation(
                    context.caseId(), classification, risk, route,
                    confidence, humanReview,
                    bounded(out.evidenceIds()),
                    citations,
                    bounded(out.missingInformation()),
                    List.of(), // ruleIds — the model never ran the rules engine
                    bounded(out.toolTraceIds()),
                    out.rationale() == null ? "" : out.rationale());
        } catch (IllegalArgumentException | NullPointerException ex) {
            // Domain contract violations — including requireNonNull on a
            // missing mandatory field — are invalid output, not invocation
            // failures.
            throw new InvalidModelOutputException(
                    "model output violates the recommendation contract: "
                            + ex.getMessage(), ex);
        }
    }

    /**
     * Resolve model-supplied chunk ids against the passages actually
     * retrieved for THIS investigation. Every citation field comes from the
     * trusted {@link PolicyPassage}; an unknown or foreign chunk id means
     * the model fabricated it, so the whole output is invalid.
     */
    private static List<PolicyCitation> resolveCitations(
            List<String> citedChunkIds, FraudCaseContext context) {
        if (citedChunkIds == null || citedChunkIds.isEmpty()) {
            return List.of();
        }
        List<PolicyCitation> resolved = new ArrayList<>();
        for (String chunkId : citedChunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            PolicyPassage passage = context.policyPassages().stream()
                    .filter(p -> p.chunkId().equals(chunkId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidModelOutputException(
                            "model cited a policy passage that was not "
                                    + "retrieved for this case: " + chunkId));
            resolved.add(PolicyCitation.from(passage));
        }
        return List.copyOf(resolved);
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String value, String field) {
        try {
            return value == null ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidModelOutputException(
                    "unknown " + field + " value: " + value, ex);
        }
    }

    private static List<String> bounded(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    private String systemPromptText() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("system prompt resource is unreadable", ex);
        }
    }
}
