package com.example.fraudcase.memory;

import com.example.fraudcase.domain.CaseMessage;
import com.example.fraudcase.domain.ConversationRole;
import com.example.fraudcase.persistence.CaseMessageEntity;
import com.example.fraudcase.persistence.CaseMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Spring AI {@link ChatMemoryRepository} backed by the case-scoped
 * {@code case_message} table. The {@code conversationId} IS the caseId —
 * conversation memory is keyed by case and nothing else.
 *
 * <p><b>Two views over one record:</b>
 * <ul>
 *   <li>{@code findByConversationId} returns the bounded <i>active memory
 *       window</i> — at most {@code windowSize} most recent messages. This
 *       is what {@code MessageWindowChatMemory.get()} exposes to the
 *       follow-up agent.</li>
 *   <li>The underlying table keeps the <i>full</i> audit-grade record;
 *       retention never deletes rows.</li>
 * </ul>
 *
 * <p>{@code saveAll} is merge-append: messages already present in the
 * record are never duplicated and earlier history is never truncated away
 * — even though {@code MessageWindowChatMemory.add()} passes only the
 * truncated window. {@code deleteByConversationId} is a deliberate no-op:
 * conversation history is immutable.
 */
public class CaseChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log =
            LoggerFactory.getLogger(CaseChatMemoryRepository.class);

    private final CaseConversationStore store;
    private final CaseMessageRepository repository;
    private final int windowSize;

    public CaseChatMemoryRepository(CaseConversationStore store,
                                    CaseMessageRepository repository,
                                    int windowSize) {
        this.store = store;
        this.repository = repository;
        this.windowSize = windowSize;
    }

    @Override
    public List<String> findConversationIds() {
        return repository.findDistinctCaseIds();
    }

    /**
     * Bounded read: the last {@code windowSize} messages for THIS case.
     * The query is case-scoped at the data layer — foreign history can
     * never enter the window.
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<CaseMessageEntity> all =
                repository.findByCaseIdOrderBySequenceNumberAsc(conversationId);
        int from = Math.max(0, all.size() - windowSize);
        return all.subList(from, all.size()).stream()
                .map(CaseChatMemoryRepository::toSpringMessage)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Append-only merge. The full record is the source of truth: items in
     * {@code messages} that overlap the tail of the stored record are
     * already persisted; only genuinely new items are appended with the
     * next case-local sequence numbers. The truncated window passed by
     * {@code MessageWindowChatMemory} can therefore never erase history.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<CaseMessage> existing = store.history(conversationId);
        int overlap = longestTailOverlap(existing, messages);
        for (Message m : messages.subList(overlap, messages.size())) {
            ConversationRole role = toRole(m);
            if (role == null) {
                continue;
            }
            store.append(conversationId, role, m.getText(), null, null);
        }
    }

    /**
     * Conversation history is immutable and append-only: this method is
     * intentionally a no-op. There is no supported way to erase a case's
     * conversation record.
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        log.warn("case-memory delete ignored for conversation (history is "
                + "append-only): conversationId={}", conversationId);
    }

    private static ConversationRole toRole(Message message) {
        return switch (message.getMessageType()) {
            case USER -> ConversationRole.USER;
            case ASSISTANT -> ConversationRole.ASSISTANT;
            default -> null; // system/tool messages are never persisted
        };
    }

    private static Message toSpringMessage(CaseMessageEntity e) {
        return switch (e.getRole()) {
            case USER -> new UserMessage(e.getContent());
            case ASSISTANT -> new AssistantMessage(e.getContent());
            default -> null;
        };
    }

    /**
     * Largest k such that {@code incoming[0..k)} equals the last k items of
     * {@code existing} (role + text). The remainder of {@code incoming} is
     * new and must be appended.
     */
    private static int longestTailOverlap(List<CaseMessage> existing,
                                          List<Message> incoming) {
        int max = Math.min(existing.size(), incoming.size());
        for (int k = max; k > 0; k--) {
            boolean overlap = true;
            for (int i = 0; i < k; i++) {
                CaseMessage e = existing.get(existing.size() - k + i);
                Message m = incoming.get(i);
                ConversationRole role = toRole(m);
                if (role != e.role() || !e.content().equals(m.getText())) {
                    overlap = false;
                    break;
                }
            }
            if (overlap) {
                return k;
            }
        }
        return 0;
    }
}
