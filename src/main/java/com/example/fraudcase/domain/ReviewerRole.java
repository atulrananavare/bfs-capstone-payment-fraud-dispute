package com.example.fraudcase.domain;

/**
 * Authorisation boundary for review operations. Only {@link #REVIEWER} may
 * submit a final disposition; {@link #AUDITOR} has read-only history access;
 * {@link #ANALYST} cannot disposition cases.
 */
public enum ReviewerRole {

    ANALYST,
    REVIEWER,
    AUDITOR
}
