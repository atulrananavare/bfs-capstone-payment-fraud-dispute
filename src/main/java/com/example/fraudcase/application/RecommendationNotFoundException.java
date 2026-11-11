package com.example.fraudcase.application;

/**
 * Thrown when a case has no persisted recommendation yet.
 */
public class RecommendationNotFoundException extends RuntimeException {

    public RecommendationNotFoundException(String caseId) {
        super("No recommendation exists for case " + caseId);
    }
}
