package com.example.fraudcase.application;

import com.example.fraudcase.agent.common.FollowUpAgent;
import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.agent.langchain4j.LangChain4jFollowUpAgent;
import com.example.fraudcase.agent.langchain4j.LangChain4jFraudCaseAgent;
import com.example.fraudcase.agent.langchain4j.LangChain4jModelGate;
import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiFollowUpAgent;
import com.example.fraudcase.agent.springai.SpringAiFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiModelGate;
import com.example.fraudcase.config.CapstoneProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration-driven selection of the {@link FraudCaseProcessor} and the
 * conversational {@link FollowUpAgent} — one place maps
 * {@code capstone.processor-mode} to the adapter pair.
 *
 * <p>{@code rules} resolves the deterministic rules adapter and never
 * touches a model or provider. {@code spring-ai} / {@code langchain4j}
 * resolve the corresponding AI adapter pair and additionally require a
 * configured model at startup — a missing model fails fast with a clear
 * configuration error instead of silently degrading to rules mode.
 */
@Component
public class ProcessorSelector {

    private final CapstoneProperties properties;
    private final RulesFraudCaseAgent rulesAgent;
    private final SpringAiFraudCaseAgent springAiAgent;
    private final LangChain4jFraudCaseAgent langChain4jAgent;
    private final SpringAiFollowUpAgent springAiFollowUpAgent;
    private final LangChain4jFollowUpAgent langChain4jFollowUpAgent;

    public ProcessorSelector(CapstoneProperties properties,
                             RulesFraudCaseAgent rulesAgent,
                             SpringAiFraudCaseAgent springAiAgent,
                             LangChain4jFraudCaseAgent langChain4jAgent,
                             SpringAiFollowUpAgent springAiFollowUpAgent,
                             LangChain4jFollowUpAgent langChain4jFollowUpAgent,
                             SpringAiModelGate springAiModelGate,
                             LangChain4jModelGate langChain4jModelGate) {
        this.properties = properties;
        this.rulesAgent = rulesAgent;
        this.springAiAgent = springAiAgent;
        this.langChain4jAgent = langChain4jAgent;
        this.springAiFollowUpAgent = springAiFollowUpAgent;
        this.langChain4jFollowUpAgent = langChain4jFollowUpAgent;

        if (properties.getProcessorMode() == CapstoneProperties.ProcessorMode.SPRING_AI
                && !springAiModelGate.modelAvailable()) {
            throw new ProcessorModeUnavailableException(
                    "SPRING_AI: no ChatModel is configured. Set "
                            + "spring.ai.anthropic.api-key (and options) or use "
                            + "capstone.processor-mode=rules.");
        }
        if (properties.getProcessorMode() == CapstoneProperties.ProcessorMode.LANGCHAIN4J
                && !langChain4jModelGate.modelAvailable()) {
            throw new ProcessorModeUnavailableException(
                    "LANGCHAIN4J: no ChatModel is configured. Set "
                            + "capstone.langchain4j.anthropic.api-key (and options) "
                            + "or use capstone.processor-mode=rules.");
        }
    }

    public FraudCaseProcessor processor() {
        return switch (properties.getProcessorMode()) {
            case SPRING_AI -> springAiAgent;
            case LANGCHAIN4J -> langChain4jAgent;
            case RULES -> rulesAgent;
        };
    }

    /**
     * The follow-up agent for the configured mode. Rules mode has no
     * conversational path — callers gate on
     * {@link #conversationAvailable()} first.
     */
    public FollowUpAgent followUpAgent() {
        return switch (properties.getProcessorMode()) {
            case SPRING_AI -> springAiFollowUpAgent;
            case LANGCHAIN4J -> langChain4jFollowUpAgent;
            case RULES -> throw new ConversationUnavailableException("rules");
        };
    }

    /** Conversational answering requires an AI processor mode. */
    public boolean conversationAvailable() {
        return properties.getProcessorMode() != CapstoneProperties.ProcessorMode.RULES;
    }
}
