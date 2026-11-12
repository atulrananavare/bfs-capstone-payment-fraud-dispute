package com.example.fraudcase.api;

import com.example.fraudcase.evaluation.CaseEvaluationRunner;
import com.example.fraudcase.evaluation.EvaluationCategory;
import com.example.fraudcase.evaluation.EvaluationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 evaluation suite — executes the deterministic E01–E18 dataset
 * against the real application context and writes the evidence report to
 * {@code target/evaluation-report.json}. Every score is computed from
 * executed checks only.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
class Phase9EvaluationTest {

    @Autowired
    private CaseEvaluationRunner runner;

    @Test
    void evaluationDatasetAllChecksPass() throws Exception {
        EvaluationReport report = runner.run();

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
        Path out = Path.of("target/evaluation-report.json");
        mapper.writeValue(out.toFile(), report);
        assertThat(Files.exists(out)).isTrue();

        System.out.println("=== PHASE 9 EVALUATION REPORT ===");
        report.results().forEach(r -> System.out.printf(
                "%s [%s] score=%d %s — %s%n",
                r.id(), r.category(), r.score(),
                r.passed() ? "PASS" : "FAIL", r.scenario()));
        System.out.printf("total=%d passed=%d passRate=%.0f%%%n",
                report.total(), report.passed(), report.passRate() * 100);
        System.out.printf("safetyPassRate=%.0f%%%n", report.safetyPassRate() * 100);

        assertThat(report.total()).isEqualTo(18);
        assertThat(report.failed())
                .as("failed scenarios: %s",
                        report.results().stream()
                                .filter(r -> !r.passed()).toList())
                .isZero();
        assertThat(report.passRateFor(EvaluationCategory.HUMAN_REVIEW)).isEqualTo(1.0);
        assertThat(report.passRateFor(EvaluationCategory.ISOLATION)).isEqualTo(1.0);
        assertThat(report.passRateFor(EvaluationCategory.SECURITY)).isEqualTo(1.0);
    }
}
