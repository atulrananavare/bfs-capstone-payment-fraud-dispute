package com.example.fraudcase.evaluation;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated evaluation results with computed summary metrics only —
 * every number derives from executed scenario results, nothing fabricated.
 */
public record EvaluationReport(
        OffsetDateTime generatedAt,
        List<EvaluationResult> results,
        int total,
        int passed,
        int failed,
        double passRate,
        Map<String, CategorySummary> categorySummaries) {

    public record CategorySummary(int total, int passed, double passRate) {
    }

    public static EvaluationReport of(List<EvaluationResult> results) {
        List<EvaluationResult> safe = List.copyOf(results);
        int total = safe.size();
        long passed = safe.stream().filter(EvaluationResult::passed).count();
        Map<EvaluationCategory, int[]> byCategory = new EnumMap<>(EvaluationCategory.class);
        for (EvaluationResult r : safe) {
            int[] counts = byCategory.computeIfAbsent(r.category(), k -> new int[2]);
            counts[0]++;
            if (r.passed()) {
                counts[1]++;
            }
        }
        Map<String, CategorySummary> summaries = new LinkedHashMap<>();
        byCategory.forEach((category, counts) -> summaries.put(
                category.name(),
                new CategorySummary(counts[0], counts[1],
                        counts[0] == 0 ? 0.0 : (double) counts[1] / counts[0])));
        return new EvaluationReport(OffsetDateTime.now(), safe, total,
                (int) passed, total - (int) passed,
                total == 0 ? 0.0 : (double) passed / total,
                Map.copyOf(summaries));
    }

    /** Pass rate restricted to the safety-control categories. */
    public double safetyPassRate() {
        long total = results.stream()
                .filter(r -> r.category() != EvaluationCategory.FUNCTIONAL).count();
        long passed = results.stream()
                .filter(r -> r.category() != EvaluationCategory.FUNCTIONAL
                        && r.passed()).count();
        return total == 0 ? 0.0 : (double) passed / total;
    }

    public double passRateFor(EvaluationCategory category) {
        CategorySummary s = categorySummaries.get(category.name());
        return s == null ? 0.0 : s.passRate();
    }
}
