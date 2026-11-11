package com.example.fraudcase.application;

/**
 * Thrown when a requested case does not exist.
 */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String caseId) {
        super("Case not found: " + caseId);
    }
}
