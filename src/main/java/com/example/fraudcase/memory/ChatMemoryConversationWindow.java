package com.example.fraudcase.memory;

import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.domain.ConversationTurn;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI-backed {@link ConversationWindow} — active only in
 * {@code spring-ai} mode. Reads the bounded window through the case-keyed
 * {@link ChatMemory} ({@code conversationId == caseId}); only
 * USER/ASSISTANT turns are surfaced.
 */
@Component
@ConditionalOnProperty(name = "capstone.processor-mode", havingValue = "spring-ai")
public class ChatMemoryConversationWindow implements ConversationWindow {

    private final ChatMemory chatMemory;

    public ChatMemoryConversationWindow(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @Override
    public List<ConversationTurn> load(String caseId) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (Message m : chatMemory.get(caseId)) {
            ConversationRole role = switch (m.getMessageType()) {
                case USER -> ConversationRole.USER;
                case ASSISTANT -> ConversationRole.ASSISTANT;
                default -> null;
            };
            if (role != null && m.getText() != null) {
                turns.add(new ConversationTurn(role, m.getText()));
            }
        }
        return List.copyOf(turns);
    }
}
