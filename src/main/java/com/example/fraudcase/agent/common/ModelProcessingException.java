package com.example.fraudcase.agent.common;

/**
 * An AI processor could not produce a recommendation/answer at all:
 * provider/model failure, tool-loop bound exceeded, or another
 * adapter-internal error. Framework-neutral so the application layer can
 * reason about "the model path failed" without importing adapter types.
 * Triggers the deterministic rules fallback upstream.
 */
public class ModelProcessingException extends RuntimeException {

    public ModelProcessingException(String message) {
        super(message);
    }

    public ModelProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
