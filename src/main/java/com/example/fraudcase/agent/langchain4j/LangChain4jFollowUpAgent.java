package com.example.fraudcase.agent.langchain4j;

import com.example.fraudcase.agent.common.AiFollowUpOutput;
import com.example.fraudcase.agent.common.CasePromptBuilder;
import com.example.fraudcase.agent.common.FollowUpAgent;
import com.example.fraudcase.agent.common.FollowUpPromptBuilder;
import com.example.fraudcase.agent.common.InvalidModelOutputException;
import com.example.fraudcase.agent.common.JsonOutputContract;
import com.example.fraudcase.agent.common.ModelOutputMappers;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.FollowUpAnswer;
import com.example.fraudcase.domain.FollowUpContext;
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
 * LangChain4j conversational follow-up — behavioural parity with
 * {@code SpringAiFollowUpAgent}: same prompt resources, same
 * trusted/untrusted split, same six case-bound read-only tools, same
 * bounded tool-call loop, same output contract and the same rejection
 * semantics via {@link ModelOutputMappers}. Scope is fixed by the trusted
 * {@link FollowUpContext}: the model cannot change caseId, memory or
 * authorisation scope.
 */
@Component
public class LangChain4jFollowUpAgent implements FollowUpAgent {

    private static final Logger log =
            LoggerFactory.getLogger(LangChain4jFollowUpAgent.class);

    /** Same leak markers as the Spring AI adapter — same system prompt. */
    private static final List<String> PROMPT_LEAK_MARKERS = List.of(
            "CASE FOLLOW-UP ASSISTANT",
            "Non-negotiable rules");

    private final ObjectProvider<ChatModel> chatModel;
    private final LangChain4jToolAdapter toolAdapter;
    private final AuditService auditService;
    private final CapstoneProperties properties;
    private final Resource systemPromptResource;
    private final CaseMetrics metrics;

    public LangChain4jFollowUpAgent(ObjectProvider<ChatModel> chatModel,
                                    LangChain4jToolAdapter toolAdapter,
                                    AuditService auditService,
                                    CapstoneProperties properties,
                                    @Value("classpath:prompts/follow-up-system-prompt.st")
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
    public FollowUpAnswer answer(FollowUpContext context) {
        Timer.Sample sample = metrics.start();
        metrics.aiRequest("followup");
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            throw new LangChain4jProcessingException(
                    "no ChatModel is configured for langchain4j mode");
        }
        String caseId = context.caseId();
        auditService.record(caseId, AuditEventType.MODEL_INVOCATION_STARTED,
                ActorType.SYSTEM, "follow-up|" + context.correlationId()
                        + "|history=" + context.history().size());
        try {
            FollowUpAnswer result = invoke(model, context);
            auditService.record(caseId, AuditEventType.MODEL_INVOCATION_COMPLETED,
                    ActorType.SYSTEM, "follow-up|" + context.correlationId()
                            + "|evidence=" + result.evidenceIds().size()
                            + "|citations=" + result.policyCitations().size());
            metrics.recordAiDuration(sample, "followup");
            return result;
        } catch (LangChain4jProcessingException | InvalidModelOutputException ex) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            // Provider/network/SDK failures — log the type only.
            log.warn("AI_REQUEST_FAILED caseId={} operation=followup type={}",
                    caseId, ex.getClass().getSimpleName());
            throw new LangChain4jProcessingException(
                    "model invocation failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private FollowUpAnswer invoke(ChatModel model, FollowUpContext context) {
        String systemText = CasePromptBuilder.systemPrompt(
                systemPromptText(),
                JsonOutputContract.formatFor(AiFollowUpOutput.class));
        String userText = FollowUpPromptBuilder.userPrompt(context);

        List<LangChain4jToolAdapter.BoundTool> bound = toolAdapter.toolsFor(
                context.fraudCase(), context.correlationId());
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
                        "follow-up did not finish within the tool-call round "
                                + "bound (" + maxRounds + ")");
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
            throw new InvalidModelOutputException(
                    "model returned empty follow-up output");
        }

        AiFollowUpOutput output =
                JsonOutputContract.parse(text, AiFollowUpOutput.class);
        return ModelOutputMappers.toFollowUpAnswer(
                output, context, PROMPT_LEAK_MARKERS);
    }

    private String systemPromptText() {
        try {
            return systemPromptResource.getContentAsString(
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "follow-up system prompt resource is unreadable", ex);
        }
    }
}
