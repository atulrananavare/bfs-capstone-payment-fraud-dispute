package com.example.fraudcase.domain;

/**
 * Structured outcome status of a tool execution. Failures are explicit
 * states — a failed tool never fabricates evidence.
 */
public enum ToolStatus {

    SUCCESS,
    NOT_FOUND,
    UNAVAILABLE,
    TIMEOUT,
    INVALID_REQUEST
}
