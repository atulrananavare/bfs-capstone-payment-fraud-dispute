package com.example.fraudcase.memory;

import com.example.fraudcase.domain.CaseMessage;
import com.example.fraudcase.domain.ConversationRole;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j {@link ChatMemoryStore} backed by the same case-scoped,
 * append-only {@link CaseConversationStore} as every other memory path.
 * Active only in {@code langchain4j} mode.
 *
 * <p>Reads translate persisted case messages into framework messages keyed
 * strictly by {@code memoryId == caseId}. Writes are deliberately refused:
 * the only append path is {@code CaseConversationStore.append} under the
 * per-case lock — memory writes must never bypass sequencing, idempotency
 * or audit.
 */
@Component
@ConditionalOnProperty(name = "capstone.processor-mode", havingValue = "langchain4j")
public class Lc4jCaseChatMemoryStore implements ChatMemoryStore {

    private final CaseConversationStore store;

    public Lc4jCaseChatMemoryStore(CaseConversationStore store) {
        this.store = store;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return store.history(String.valueOf(memoryId)).stream()
                .map(Lc4jCaseChatMemoryStore::toChatMessage)
                .toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        throw new UnsupportedOperationException(
                "conversation writes go through CaseConversationStore "
                        + "append path only");
    }

    @Override
    public void deleteMessages(Object memoryId) {
        throw new UnsupportedOperationException(
                "conversation record is append-only; delete is not permitted");
    }

    private static ChatMessage toChatMessage(CaseMessage m) {
        if (m.role() == ConversationRole.ASSISTANT) {
            return AiMessage.from(m.content());
        }
        return UserMessage.from(m.content());
    }
}
