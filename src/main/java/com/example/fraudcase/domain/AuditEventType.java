package com.example.fraudcase.domain;

/**
 * Types of events recorded in the append-only audit trail.
 */
public enum AuditEventType {

    CASE_CREATED,
    CASE_STATE_CHANGED,
    INVESTIGATION_STARTED,
    TOOL_EXECUTED,
    TOOL_FAILED,
    RECOMMENDATION_CREATED,
    RECOMMENDATION_REJECTED
}
