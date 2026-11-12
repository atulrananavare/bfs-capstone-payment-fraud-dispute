package com.example.fraudcase.agent.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Deterministic test double for {@link ChatModel}. Queues scripted
 * {@link ChatResponse}s or {@link RuntimeException}s and records every
 * prompt it receives — no network, no provider, no credentials.
 */
public class StubChatModel implements ChatModel {

    private final Queue<Object> scripted = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();

    public void enqueueResponse(ChatResponse response) {
        scripted.add(response);
    }

    public void enqueueJson(String json) {
        scripted.add(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content(json).build()))));
    }

    public void enqueueToolCalls(String... calls) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < calls.length; i += 2) {
            toolCalls.add(new AssistantMessage.ToolCall(
                    "call-" + i, "function", calls[i], calls[i + 1]));
        }
        scripted.add(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(toolCalls).build()))));
    }

    public void enqueueFailure(RuntimeException ex) {
        scripted.add(ex);
    }

    /** Deferred scripting: the function runs when the model is invoked, so
     *  output can depend on state created during the investigation. */
    public void enqueueFunction(java.util.function.Function<Prompt, ChatResponse> fn) {
        scripted.add(fn);
    }

    public void reset() {
        scripted.clear();
        prompts.clear();
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    public boolean isExhausted() {
        return scripted.isEmpty();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Object next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("StubChatModel script exhausted");
        }
        if (next instanceof RuntimeException ex) {
            throw ex;
        }
        if (next instanceof java.util.function.Function<?, ?> fn) {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Prompt, ChatResponse> f =
                    (java.util.function.Function<Prompt, ChatResponse>) fn;
            return f.apply(prompt);
        }
        return (ChatResponse) next;
    }
}
