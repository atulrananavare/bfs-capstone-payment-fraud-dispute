package com.example.fraudcase.application;

/**
 * The review references a recommendation that is not the case's current
 * validated recommendation — stale context or a foreign recommendation id.
 */
public class StaleRecommendationException extends RuntimeException {

    public StaleRecommendationException(String message) {
        super(message);
    }
}
