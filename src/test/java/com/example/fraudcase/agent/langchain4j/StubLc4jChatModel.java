package com.example.fraudcase.agent.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Deterministic test double for {@link ChatModel} — the LangChain4j
 * counterpart of {@code StubChatModel}. Queues scripted
 * {@link ChatResponse}s or {@link RuntimeException}s and records every
 * request — no network, no provider, no credentials.
 */
public class StubLc4jChatModel implements ChatModel {

    private final Queue<Object> scripted = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    public void enqueueResponse(ChatResponse response) {
        scripted.add(response);
    }

    public void enqueueJson(String json) {
        scripted.add(ChatResponse.builder()
                .aiMessage(AiMessage.from(json))
                .build());
    }

    public void enqueueToolCalls(String... calls) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (int i = 0; i < calls.length; i += 2) {
            requests.add(ToolExecutionRequest.builder()
                    .id("call-" + i)
                    .name(calls[i])
                    .arguments(calls[i + 1])
                    .build());
        }
        scripted.add(ChatResponse.builder()
                .aiMessage(new AiMessage(requests))
                .build());
    }

    public void enqueueFailure(RuntimeException ex) {
        scripted.add(ex);
    }

    /** Deferred scripting: the function runs when the model is invoked, so
     *  output can depend on state created during the investigation. */
    public void enqueueFunction(
            java.util.function.Function<ChatRequest, ChatResponse> fn) {
        scripted.add(fn);
    }

    public void reset() {
        scripted.clear();
        requests.clear();
    }

    public List<ChatRequest> requests() {
        return requests;
    }

    public boolean isExhausted() {
        return scripted.isEmpty();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        Object next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("StubLc4jChatModel script exhausted");
        }
        if (next instanceof RuntimeException ex) {
            throw ex;
        }
        if (next instanceof java.util.function.Function<?, ?> fn) {
            @SuppressWarnings("unchecked")
            java.util.function.Function<ChatRequest, ChatResponse> f =
                    (java.util.function.Function<ChatRequest, ChatResponse>) fn;
            return f.apply(request);
        }
        return (ChatResponse) next;
    }
}
