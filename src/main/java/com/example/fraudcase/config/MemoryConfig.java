package com.example.fraudcase.config;

import com.example.fraudcase.memory.CaseChatMemoryRepository;
import com.example.fraudcase.memory.CaseConversationStore;
import com.example.fraudcase.persistence.CaseMessageRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 5 case-scoped conversation memory wiring.
 *
 * <p>Spring AI's {@link MessageWindowChatMemory} is the bounded active
 * memory: {@code conversationId} is always a caseId and the repository
 * view already applies the configured window on read. The window size
 * comes exclusively from {@code capstone.memory.max-messages} — nothing
 * is hard-coded. These beans are provider-free, so they exist in every
 * processor mode; the conversational answering path itself is only
 * reachable in spring-ai mode.
 */
@Configuration
public class MemoryConfig {

    @Bean
    ChatMemoryRepository chatMemoryRepository(CaseConversationStore store,
                                              CaseMessageRepository repository,
                                              CapstoneProperties properties) {
        return new CaseChatMemoryRepository(store, repository,
                properties.getMemory().getMaxMessages());
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository,
                          CapstoneProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.getMemory().getMaxMessages())
                .build();
    }
}
