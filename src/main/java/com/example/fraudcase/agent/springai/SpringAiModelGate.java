package com.example.fraudcase.agent.springai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Boundary that lets application code ask "is a model configured?" without
 * importing Spring AI types outside the adapter package. Rules mode never
 * resolves a model; the provider is touched only when it is actually
 * configured.
 */
@Component
public class SpringAiModelGate {

    private final ObjectProvider<ChatModel> chatModel;

    public SpringAiModelGate(ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
    }

    public boolean modelAvailable() {
        return chatModel.getIfAvailable() != null;
    }
}
