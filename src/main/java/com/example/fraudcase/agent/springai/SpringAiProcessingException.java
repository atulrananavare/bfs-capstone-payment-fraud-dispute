package com.example.fraudcase.agent.springai;

/**
 * Spring AI mode could not produce a recommendation at all: provider/model
 * failure, tool-loop bound exceeded, or another adapter-internal error.
 * Triggers the deterministic rules fallback upstream.
 */
public class SpringAiProcessingException extends RuntimeException {

    public SpringAiProcessingException(String message) {
        super(message);
    }

    public SpringAiProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
