package com.example.fraudcase.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j wiring — the only place provider types are constructed.
 * Mirrors {@code SpringAiAdapterConfig} exactly:
 *
 * <p>The {@link ChatModel} bean exists only when ALL of the following hold:
 * <ul>
 *   <li>{@code capstone.processor-mode=langchain4j}</li>
 *   <li>{@code capstone.langchain4j.anthropic.api-key} is set (environment
 *       variable; never committed)</li>
 *   <li>no other {@link ChatModel} bean exists (tests may inject a stub)</li>
 * </ul>
 * In rules or spring-ai mode — or without a key — no provider client is
 * created, so the application starts with zero credentials. If langchain4j
 * mode is selected without a model, {@code ProcessorSelector} fails
 * startup with a clear configuration error.
 */
@Configuration
public class LangChain4jAdapterConfig {

    @Bean
    @ConditionalOnProperty(name = "capstone.processor-mode", havingValue = "langchain4j")
    @ConditionalOnProperty("capstone.langchain4j.anthropic.api-key")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel langChain4jAnthropicChatModel(
            @Value("${capstone.langchain4j.anthropic.api-key}") String apiKey,
            @Value("${capstone.langchain4j.anthropic.model:claude-sonnet-4-5}")
            String model,
            CapstoneProperties properties) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(properties.getAi().getTemperature())
                .maxTokens(properties.getAi().getMaxOutputTokens())
                .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                .build();
    }
}
