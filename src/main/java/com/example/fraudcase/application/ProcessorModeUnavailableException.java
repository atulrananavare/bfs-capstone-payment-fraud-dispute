package com.example.fraudcase.application;

/**
 * Thrown when the configured processor mode cannot run: either the mode
 * itself has no implementation, or spring-ai mode is selected without a
 * configured model. Startup fails fast with a clear configuration error —
 * the application never silently degrades.
 */
public class ProcessorModeUnavailableException extends RuntimeException {

    public ProcessorModeUnavailableException(String detail) {
        super(detail);
    }
}
