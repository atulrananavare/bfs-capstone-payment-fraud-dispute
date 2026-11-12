package com.example.fraudcase.memory;

import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.ConversationTurn;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j-backed {@link ConversationWindow} — active only in
 * {@code langchain4j} mode. Wraps {@link Lc4jCaseChatMemoryStore} in
 * {@link MessageWindowChatMemory} with {@code memoryId == caseId} and the
 * configured bound ({@code capstone.memory.max-messages}); only
 * USER/ASSISTANT turns are surfaced.
 */
@Component
@ConditionalOnProperty(name = "capstone.processor-mode", havingValue = "langchain4j")
public class Lc4jConversationWindow implements ConversationWindow {

    private final Lc4jCaseChatMemoryStore store;
    private final int maxMessages;

    public Lc4jConversationWindow(Lc4jCaseChatMemoryStore store,
                                CapstoneProperties properties) {
        this.store = store;
        this.maxMessages = properties.getMemory().getMaxMessages();
    }

    @Override
    public List<ConversationTurn> load(String caseId) {
        List<ChatMessage> window = MessageWindowChatMemory.builder()
                .id(caseId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build()
                .messages();
        return window.stream()
                .map(Lc4jConversationWindow::toTurn)
                .filter(t -> t != null)
                .toList();
    }

    private static ConversationTurn toTurn(ChatMessage m) {
        if (m instanceof UserMessage u && u.singleText() != null) {
            return new ConversationTurn(ConversationRole.USER, u.singleText());
        }
        if (m instanceof AiMessage a && a.text() != null) {
            return new ConversationTurn(ConversationRole.ASSISTANT, a.text());
        }
        return null;
    }
}
