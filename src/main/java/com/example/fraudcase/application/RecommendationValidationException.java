package com.example.fraudcase.application;

import java.util.List;

/**
 * Thrown when processor output fails {@code RecommendationValidator} checks.
 * The offending output is never persisted as an approved recommendation.
 */
public class RecommendationValidationException extends RuntimeException {

    private final List<String> violations;

    public RecommendationValidationException(List<String> violations) {
        super("Recommendation failed validation: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
