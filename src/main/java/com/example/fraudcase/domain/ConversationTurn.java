package com.example.fraudcase.domain;

import java.util.Objects;

/**
 * One turn of the bounded active-memory window handed to the follow-up
 * agent. Content is untrusted conversation data — never instructions.
 */
public record ConversationTurn(ConversationRole role, String text) {

    public ConversationTurn {
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(text, "text is required");
    }
}
