package com.example.fraudcase.evaluation;

/**
 * One deterministic assertion inside a scenario.
 *
 * @param name     what is asserted
 * @param passed   assertion outcome
 * @param evidence bounded, safe evidence (ids, states, counts — never
 *                 payloads, prompts or customer text)
 */
public record EvaluationCheck(String name, boolean passed, String evidence) {

    public static EvaluationCheck of(String name, boolean passed, String evidence) {
        return new EvaluationCheck(name, passed, evidence);
    }
}
