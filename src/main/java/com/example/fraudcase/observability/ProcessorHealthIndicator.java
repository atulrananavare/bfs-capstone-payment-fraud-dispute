package com.example.fraudcase.observability;

import com.example.fraudcase.config.CapstoneProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Safe operational health detail for the processor pipeline (Phase 9).
 * Reports only configuration metadata — the selected mode and whether a
 * model bean exists. No credentials, endpoints, model output or provider
 * internals are ever exposed; {@code show-details: never} also hides the
 * detail map from unauthenticated callers anyway.
 *
 * <p>An absent model in an AI mode reports {@code OUT_OF_SERVICE} (the app
 * fails closed in that case); in rules mode a model is optional by design
 * and never required for health.
 */
@Component("fraudcaseProcessor")
public class ProcessorHealthIndicator implements HealthIndicator {

    private final CapstoneProperties properties;
    private final ObjectProvider<org.springframework.ai.chat.model.ChatModel> springAiModel;
    private final ObjectProvider<dev.langchain4j.model.chat.ChatModel> langChain4jModel;

    public ProcessorHealthIndicator(
            CapstoneProperties properties,
            ObjectProvider<org.springframework.ai.chat.model.ChatModel> springAiModel,
            ObjectProvider<dev.langchain4j.model.chat.ChatModel> langChain4jModel) {
        this.properties = properties;
        this.springAiModel = springAiModel;
        this.langChain4jModel = langChain4jModel;
    }

    @Override
    public Health health() {
        CapstoneProperties.ProcessorMode mode = properties.getProcessorMode();
        boolean modelPresent = switch (mode) {
            case SPRING_AI -> springAiModel.getIfAvailable() != null;
            case LANGCHAIN4J -> langChain4jModel.getIfAvailable() != null;
            case RULES -> springAiModel.getIfAvailable() != null
                    || langChain4jModel.getIfAvailable() != null;
        };
        boolean aiMode = mode == CapstoneProperties.ProcessorMode.SPRING_AI
                || mode == CapstoneProperties.ProcessorMode.LANGCHAIN4J;
        if (aiMode && !modelPresent) {
            return Health.outOfService()
                    .withDetail("processorMode", mode.name())
                    .withDetail("modelConfigured", false)
                    .build();
        }
        return Health.up()
                .withDetail("processorMode", mode.name())
                .withDetail("modelConfigured", modelPresent)
                .withDetail("humanReviewRequired", true)
                .build();
    }
}
