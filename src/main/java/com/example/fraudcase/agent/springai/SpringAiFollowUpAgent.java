package com.example.fraudcase.agent.springai;

import com.example.fraudcase.agent.common.ActionClaimGuard;
import com.example.fraudcase.agent.common.AiFollowUpOutput;
import com.example.fraudcase.agent.common.CasePromptBuilder;
import com.example.fraudcase.agent.common.FollowUpAgent;
import com.example.fraudcase.agent.common.FollowUpPromptBuilder;
import com.example.fraudcase.agent.common.InvalidModelOutputException;
import com.example.fraudcase.agent.common.ModelOutputMappers;
import com.example.fraudcase.application.AuditService;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.FollowUpAnswer;
import com.example.fraudcase.domain.FollowUpContext;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyPassage;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI conversational follow-up (Phase 5). Same guarantees as the
 * investigation adapter:
 *
 * <ul>
 *   <li>Trusted instructions only in the system message; case data,
 *       conversation history and the question itself are untrusted data
 *       inside {@code <untrusted_*>} tags. A persisted malicious message
 *       reloads as DATA — never as instructions.</li>
 *   <li>Only the six allow-listed case-bound read-only tools are
 *       registered; the loop is bounded by {@code capstone.ai.max-rounds}.
 *       Nothing else can execute — conversation cannot trigger actions,
 *       state transitions or new recommendations.</li>
 *   <li>Citations may only reference chunk ids retrieved this turn or
 *       already validated for this case; every field comes from the
 *       trusted source, never from model output. Unknown ids invalidate
 *       the whole answer.</li>
 *   <li>An answer claiming an executed action, echoing the system prompt
 *       or naming foreign evidence is invalid — the caller falls back to
 *       a deterministic safe answer.</li>
 *   <li>Scope is fixed by the trusted {@link FollowUpContext}: the model
 *       cannot change caseId, memory or authorisation scope.</li>
 * </ul>
 */
@Component
public class SpringAiFollowUpAgent implements FollowUpAgent {

    private static final Logger log =
            LoggerFactory.getLogger(SpringAiFollowUpAgent.class);

    /** Distinctive phrases from the follow-up system prompt resource. */
    private static final List<String> PROMPT_LEAK_MARKERS = List.of(
            "CASE FOLLOW-UP ASSISTANT",
            "Non-negotiable rules");

    static final int MAX_ANSWER_LENGTH = 4000;

    private final ObjectProvider<ChatModel> chatModel;
    private final ToolCallingManager toolCallingManager;
    private final SpringAiToolAdapter toolAdapter;
    private final AuditService auditService;
    private final CapstoneProperties properties;
    private final Resource systemPromptResource;
    private final CaseMetrics metrics;

    public SpringAiFollowUpAgent(ObjectProvider<ChatModel> chatModel,
                                 ToolCallingManager toolCallingManager,
                                 SpringAiToolAdapter toolAdapter,
                                 AuditService auditService,
                                 CapstoneProperties properties,
                                 @Value("classpath:prompts/follow-up-system-prompt.st")
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

    public FollowUpAnswer answer(FollowUpContext context) {
        Timer.Sample sample = metrics.start();
        metrics.aiRequest("followup");
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            throw new SpringAiProcessingException(
                    "no ChatModel is configured for spring-ai mode");
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
        } catch (SpringAiProcessingException | InvalidModelOutputException ex) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.aiFailure("followup");
            metrics.recordAiDuration(sample, "followup");
            // Provider/network/SDK failures — log the type only.
            log.warn("AI_REQUEST_FAILED caseId={} operation=followup type={}",
                    caseId, ex.getClass().getSimpleName());
            throw new SpringAiProcessingException(
                    "model invocation failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private FollowUpAnswer invoke(ChatModel model, FollowUpContext context) {
        BeanOutputConverter<AiFollowUpOutput> converter =
                new BeanOutputConverter<>(AiFollowUpOutput.class);

        String systemText = CasePromptBuilder.systemPrompt(
                systemPromptText(), converter.getFormat());
        String userText = FollowUpPromptBuilder.userPrompt(context);

        // Follow-up may only consult the same six allow-listed case-bound
        // read-only tools — nothing else can execute.
        List<ToolCallback> callbacks = toolAdapter.callbacksFor(
                context.fraudCase(), context.correlationId());

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
                        "follow-up did not finish within the tool-call round "
                                + "bound (" + maxRounds + ")");
            }
            ToolExecutionResult executed =
                    toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(executed.conversationHistory(), options);
        }

        String text = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new InvalidModelOutputException(
                    "model returned empty follow-up output");
        }

        AiFollowUpOutput output;
        try {
            output = converter.convert(text);
        } catch (RuntimeException ex) {
            throw new InvalidModelOutputException(
                    "follow-up output is not valid structured JSON", ex);
        }
        if (output == null) {
            throw new InvalidModelOutputException("follow-up output did not convert");
        }
        return ModelOutputMappers.toFollowUpAnswer(
                output, context, PROMPT_LEAK_MARKERS);
    }

    private String systemPromptText() {
        try {
            return systemPromptResource.getContentAsString(
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "follow-up system prompt resource is unreadable", ex);
        }
    }
}
