package com.example.fraudcase.agent.langchain4j;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Boundary that lets application code ask "is a LangChain4j model
 * configured?" without importing framework types outside the adapter
 * package. Same contract as {@code SpringAiModelGate}.
 */
@Component
public class LangChain4jModelGate {

    private final ObjectProvider<ChatModel> chatModel;

    public LangChain4jModelGate(ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
    }

    public boolean modelAvailable() {
        return chatModel.getIfAvailable() != null;
    }
}
