package com.example.fraudcase.application;

/**
 * Thrown when the configured processor mode has no implementation in this
 * phase (e.g. spring-ai before the AI adapter exists).
 */
public class ProcessorModeUnavailableException extends RuntimeException {

    public ProcessorModeUnavailableException(String mode) {
        super("processor-mode '" + mode + "' is not implemented in this phase; use rules");
    }
}
