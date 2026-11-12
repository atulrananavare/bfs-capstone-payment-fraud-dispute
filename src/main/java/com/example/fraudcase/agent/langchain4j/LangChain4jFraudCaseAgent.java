package com.example.fraudcase.agent.langchain4j;

import com.example.fraudcase.agent.common.AiRecommendationOutput;
import com.example.fraudcase.agent.common.CasePromptBuilder;
import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.agent.common.InvalidModelOutputException;
import com.example.fraudcase.agent.common.JsonOutputContract;
import com.example.fraudcase.agent.common.ModelOutputMappers;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.observability.CaseMetrics;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j implementation of {@link FraudCaseProcessor} — behavioural
 * parity with {@code SpringAiFraudCaseAgent}:
 *
 * <ul>
 *   <li>Same trusted/untrusted prompt split ({@link CasePromptBuilder}) and
 *       the same system-prompt resource.</li>
 *   <li>Same six allow-listed case-bound tools via {@link CaseToolInvoker};
 *       the tool-call loop is owned here and bounded by
 *       {@code capstone.ai.max-rounds}.</li>
 *   <li>Same output contract ({@link AiRecommendationOutput}) parsed via
 *       {@link JsonOutputContract} and mapped by {@link ModelOutputMappers}
 *       — identical validation, identical rejection semantics.</li>
 *   <li>Failures surface as typed exceptions so the orchestrator applies
 *       the same audit + deterministic-fallback path as spring-ai mode.</li>
 *   <li>Raw prompts, raw model text and provider error details are never
 *       logged or audited — only bounded identifiers and event types.</li>
 * </ul>
 */
@Component
public class LangChain4jFraudCaseAgent implements FraudCaseProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(LangChain4jFraudCaseAgent.class);

    /** Same leak markers as the Spring AI adapter — same system prompt. */
    private static final List<String> PROMPT_LEAK_MARKERS = List.of(
            "INVESTIGATION AND RECOMMENDATION ASSISTANT",
            "Non-negotiable rules");

    private final ObjectProvider<ChatModel> chatModel;
    private final LangChain4jToolAdapter toolAdapter;
    private final AuditService auditService;
    private final CapstoneProperties properties;
    private final Resource systemPromptResource;
    private final CaseMetrics metrics;

    public LangChain4jFraudCaseAgent(ObjectProvider<ChatModel> chatModel,
                                     LangChain4jToolAdapter toolAdapter,
                                     AuditService auditService,
                                     CapstoneProperties properties,
                                     @Value("classpath:prompts/fraud-case-system-prompt.st")
                                     Resource systemPromptResource,
                                     CaseMetrics metrics) {
        this.chatModel = chatModel;
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
            throw new LangChain4jProcessingException(
                    "no ChatModel is configured for langchain4j mode");
        }
        String caseId = context.caseId();
        auditService.record(caseId, AuditEventType.MODEL_INVOCATION_STARTED,
                ActorType.SYSTEM, "langchain4j|tools="
                        + LangChain4jToolAdapter.ALLOWED_TOOL_NAMES.size()
                        + "|maxToolCalls=" + properties.getAi().getMaxToolCalls());

        try {
            FraudCaseRecommendation recommendation = invoke(model, context);
            auditService.record(caseId, AuditEventType.MODEL_INVOCATION_COMPLETED,
                    ActorType.SYSTEM, recommendation.classification()
                            + "|" + recommendation.recommendedRoute());
            metrics.recordAiDuration(sample, "investigation");
            return recommendation;
        } catch (LangChain4jProcessingException | InvalidModelOutputException ex) {
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
            throw new LangChain4jProcessingException(
                    "model invocation failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private FraudCaseRecommendation invoke(ChatModel model, FraudCaseContext context) {
        String systemText = CasePromptBuilder.systemPrompt(
                systemPromptText(),
                JsonOutputContract.formatFor(AiRecommendationOutput.class));
        String userText = CasePromptBuilder.userPrompt(context);

        List<LangChain4jToolAdapter.BoundTool> bound = toolAdapter.toolsFor(
                context.fraudCase(), context.investigationId());
        Map<String, ToolExecutor> executors = new HashMap<>();
        bound.forEach(b -> executors.put(b.specification().name(), b.executor()));

        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(systemText), UserMessage.from(userText)));

        int maxRounds = properties.getAi().getMaxRounds();
        AiMessage aiMessage = null;
        for (int round = 0; round < maxRounds; round++) {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.copyOf(messages))
                    .toolSpecifications(bound.stream()
                            .map(LangChain4jToolAdapter.BoundTool::specification)
                            .toList())
                    .temperature(properties.getAi().getTemperature())
                    .maxOutputTokens(properties.getAi().getMaxOutputTokens())
                    .build());
            aiMessage = response == null ? null : response.aiMessage();
            if (aiMessage == null) {
                throw new LangChain4jProcessingException(
                        "model returned no message");
            }
            messages.add(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                break;
            }
            if (round == maxRounds - 1) {
                throw new LangChain4jProcessingException(
                        "model did not finish within the tool-call round bound ("
                                + maxRounds + ")");
            }
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolExecutor executor = executors.get(request.name());
                String result = executor == null
                        ? "{\"tool\":\"" + request.name()
                                + "\",\"status\":\"INVALID_REQUEST\",\"attributes\":"
                                + "{\"reason\":\"tool is not allow-listed\"}}"
                        : executor.execute(request, null);
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }

        String text = aiMessage.text();
        if (text == null || text.isBlank()) {
            throw new InvalidModelOutputException("model returned empty output");
        }

        AiRecommendationOutput output =
                JsonOutputContract.parse(text, AiRecommendationOutput.class);
        return ModelOutputMappers.toRecommendation(
                output, context, PROMPT_LEAK_MARKERS);
    }

    private String systemPromptText() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("system prompt resource is unreadable", ex);
        }
    }
}
