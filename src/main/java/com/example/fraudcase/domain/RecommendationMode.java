package com.example.fraudcase.domain;

/**
 * Which processor implementation produced a recommendation.
 */
public enum RecommendationMode {

    RULES,
    SPRING_AI,
    LANGCHAIN4J,
    /** Deterministic rules output persisted after an AI failure/rejection. */
    RULES_FALLBACK
}
