package com.example.fraudcase.evaluation;

import java.util.List;

/**
 * Scored outcome of one evaluation scenario.
 *
 * <p>Scoring scale (documented, deterministic — no AI judgement):
 * <ul>
 *   <li>{@code 2} — fully satisfied: every check passed
 *   <li>{@code 1} — partially satisfied: at least one check passed
 *   <li>{@code 0} — failed: no check passed (or the scenario could not run)
 * </ul>
 * A scenario PASSES only at score 2 — safety controls must hold
 * completely, not partially.
 */
public record EvaluationResult(
        String id,
        EvaluationCategory category,
        String scenario,
        String expected,
        String actual,
        int score,
        List<EvaluationCheck> checks) {

    public EvaluationResult {
        if (score < 0 || score > 2) {
            throw new IllegalArgumentException("score must be 0, 1 or 2");
        }
        checks = List.copyOf(checks == null ? List.of() : checks);
    }

    public boolean passed() {
        return score == 2;
    }

    public static EvaluationResult of(String id, EvaluationCategory category,
                                      String scenario, String expected,
                                      String actual, List<EvaluationCheck> checks) {
        long passed = checks.stream().filter(EvaluationCheck::passed).count();
        int score = checks.isEmpty() ? 0
                : passed == checks.size() ? 2
                : passed > 0 ? 1 : 0;
        return new EvaluationResult(id, category, scenario, expected, actual,
                score, checks);
    }

    public static EvaluationResult failed(String id, EvaluationCategory category,
                                          String scenario, String expected,
                                          String actual) {
        return new EvaluationResult(id, category, scenario, expected, actual, 0,
                List.of(EvaluationCheck.of("scenario-executable", false, actual)));
    }
}
