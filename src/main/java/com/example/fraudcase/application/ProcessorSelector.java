package com.example.fraudcase.application;

import com.example.fraudcase.agent.common.FraudCaseProcessor;
import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiModelGate;
import com.example.fraudcase.config.CapstoneProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration-driven selection of the {@link FraudCaseProcessor}.
 *
 * <p>{@code capstone.processor-mode=rules} resolves the deterministic rules
 * adapter and never touches a model or provider. {@code spring-ai} resolves
 * the Spring AI adapter and additionally requires a configured model at
 * startup — a missing model fails fast with a clear configuration error
 * instead of silently degrading to rules mode.
 */
@Component
public class ProcessorSelector {

    private final CapstoneProperties properties;
    private final RulesFraudCaseAgent rulesAgent;
    private final SpringAiFraudCaseAgent springAiAgent;

    public ProcessorSelector(CapstoneProperties properties,
                             RulesFraudCaseAgent rulesAgent,
                             SpringAiFraudCaseAgent springAiAgent,
                             SpringAiModelGate modelGate) {
        this.properties = properties;
        this.rulesAgent = rulesAgent;
        this.springAiAgent = springAiAgent;
        if (properties.getProcessorMode() == CapstoneProperties.ProcessorMode.SPRING_AI
                && !modelGate.modelAvailable()) {
            throw new ProcessorModeUnavailableException(
                    "SPRING_AI: no ChatModel is configured. Set "
                            + "spring.ai.anthropic.api-key (and options) or use "
                            + "capstone.processor-mode=rules.");
        }
    }

    public FraudCaseProcessor processor() {
        return properties.getProcessorMode() == CapstoneProperties.ProcessorMode.SPRING_AI
                ? springAiAgent : rulesAgent;
    }
}
