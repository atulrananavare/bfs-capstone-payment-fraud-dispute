package com.example.fraudcase.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring AI wiring — the only place provider types are constructed.
 *
 * <p>The {@link ChatModel} bean exists only when ALL of the following hold:
 * <ul>
 *   <li>{@code capstone.processor-mode=spring-ai}</li>
 *   <li>{@code spring.ai.anthropic.api-key} is set (environment variable;
 *       never committed)</li>
 *   <li>no other {@link ChatModel} bean exists (tests may inject a stub)</li>
 * </ul>
 * In rules mode — or without a key — no provider client is created, so the
 * application starts with zero credentials. If spring-ai mode is selected
 * without a model, {@code ProcessorSelector} fails startup with a clear
 * configuration error.
 */
@Configuration
public class SpringAiAdapterConfig {

    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "capstone.processor-mode", havingValue = "spring-ai")
    @ConditionalOnProperty("spring.ai.anthropic.api-key")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel anthropicChatModel(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5}")
            String model,
            CapstoneProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) properties.getAi().getTimeoutMs();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(properties.getAi().getTemperature())
                        .maxTokens(properties.getAi().getMaxOutputTokens())
                        .build())
                .build();
    }
}
