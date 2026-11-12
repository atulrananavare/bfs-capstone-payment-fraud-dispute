package com.example.fraudcase.domain;

/**
 * Typed roles for case-scoped conversation messages.
 *
 * <p>Only {@link #USER} and {@link #ASSISTANT} are persisted as
 * {@code case_message} rows. {@link #SYSTEM_CONTEXT},
 * {@link #TOOL_EVIDENCE} and {@link #POLICY_CONTEXT} are transient prompt
 * sections rebuilt per request — they are never stored as conversation and
 * never appear in history responses.
 */
public enum ConversationRole {
    /** Analyst/user input. Untrusted data — never an instruction. */
    USER,
    /** Approved user-visible assistant answer (never raw model output). */
    ASSISTANT,
    /** Transient trusted instructions — system message only. */
    SYSTEM_CONTEXT,
    /** Transient tool outcome context — untrusted data. */
    TOOL_EVIDENCE,
    /** Transient retrieved policy context — untrusted data. */
    POLICY_CONTEXT
}
