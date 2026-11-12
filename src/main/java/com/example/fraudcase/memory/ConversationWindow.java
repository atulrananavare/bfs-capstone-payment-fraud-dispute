package com.example.fraudcase.memory;

import com.example.fraudcase.domain.ConversationTurn;

import java.util.List;

/**
 * Framework-neutral read port for the bounded active conversation window —
 * the counterpart of {@code FraudCaseProcessor} for memory. The
 * application layer asks for "the current case's window" without knowing
 * which framework serves it:
 *
 * <ul>
 *   <li>{@code spring-ai} mode → Spring AI {@code ChatMemory} implementation</li>
 *   <li>{@code langchain4j} mode → LangChain4j {@code MessageWindowChatMemory}
 *       + {@code ChatMemoryStore} implementation</li>
 *   <li>{@code rules} mode → no bean; conversation is unavailable anyway</li>
 * </ul>
 *
 * <p>{@code caseId} is the only key — same isolation rule as every other
 * memory path.
 */
public interface ConversationWindow {

    List<ConversationTurn> load(String caseId);
}
