package com.example.fraudcase.observability;

import com.example.fraudcase.config.CapstoneProperties;
import org.springframework.ai.chat.model.ChatModel;
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
 * <p>An absent ChatModel in spring-ai mode reports {@code OUT_OF_SERVICE}
 * (the app fails closed in that case); in rules mode a model is optional
 * by design and never required for health.
 */
@Component("fraudcaseProcessor")
public class ProcessorHealthIndicator implements HealthIndicator {

    private final CapstoneProperties properties;
    private final ObjectProvider<ChatModel> chatModel;

    public ProcessorHealthIndicator(CapstoneProperties properties,
                                    ObjectProvider<ChatModel> chatModel) {
        this.properties = properties;
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        CapstoneProperties.ProcessorMode mode = properties.getProcessorMode();
        boolean modelPresent = chatModel.getIfAvailable() != null;
        if (mode == CapstoneProperties.ProcessorMode.SPRING_AI && !modelPresent) {
            return Health.outOfService()
                    .withDetail("processorMode", "SPRING_AI")
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
