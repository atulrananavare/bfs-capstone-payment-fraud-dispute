package com.example.fraudcase.evaluation;

/**
 * Evaluation dimensions (Phase 9). Functional checks measure workflow
 * correctness; the other categories measure safety/business controls.
 */
public enum EvaluationCategory {
    FUNCTIONAL,
    ISOLATION,
    HUMAN_REVIEW,
    SECURITY,
    FAILURE,
    AI_SAFETY
}
