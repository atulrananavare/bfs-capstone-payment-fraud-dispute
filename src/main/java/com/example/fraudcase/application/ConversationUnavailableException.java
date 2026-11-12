package com.example.fraudcase.application;

/**
 * Conversational follow-up requires the spring-ai processor path. In
 * rules mode the endpoint is deliberately unavailable — the deterministic
 * {@code RulesFraudCaseAgent} must never depend on a model, and no
 * conversational answer is invented locally.
 */
public class ConversationUnavailableException extends RuntimeException {

    public ConversationUnavailableException(String mode) {
        super("conversational follow-up requires spring-ai processor mode; "
                + "configured mode is " + mode);
    }
}
