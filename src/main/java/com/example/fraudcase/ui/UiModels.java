package com.example.fraudcase.ui;

import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.ToolStatus;

import java.util.List;

/**
 * Safe view-model helpers for the analyst workbench. Everything here is
 * display-only — no business rule lives in the UI layer.
 */
public final class UiModels {

    private UiModels() {
    }

    /** One workflow-navigation entry. {@code status} is textual so state
     *  never depends on colour alone; {@code enabled} decides link vs text. */
    public record NavItem(String key, String label, String url,
                          String status, boolean enabled, boolean current) {
    }

    /**
     * Builds the persistent left-hand workflow nav. Completion is derived
     * from persisted artifacts only — opening a page never marks a step
     * done.
     */
    public static List<NavItem> workflowNav(
            String caseId, String currentKey, boolean investigated,
            boolean hasRecommendation, boolean hasEvidence,
            boolean canReview, boolean hasReviews) {
        return List.of(
                item("intake", "1 Case Intake",
                        "/ui/cases/" + caseId, true, currentKey,
                        "Done"),
                item("investigation", "2 Investigation",
                        "/ui/cases/" + caseId + "/investigation", true,
                        currentKey, investigated ? "Done" : "Pending"),
                item("recommendation", "3 Recommendation",
                        "/ui/cases/" + caseId + "/recommendation",
                        investigated, currentKey,
                        hasRecommendation ? "Done" : "Not available"),
                item("evidence", "4 Evidence",
                        "/ui/cases/" + caseId + "/evidence",
                        investigated, currentKey,
                        hasEvidence ? "Done" : "Not available"),
                item("policy", "5 Policy Citations",
                        "/ui/cases/" + caseId + "/policy",
                        investigated, currentKey,
                        investigated ? "Done" : "Not available"),
                item("review", "6 Human Review",
                        "/ui/cases/" + caseId + "/review",
                        investigated, currentKey,
                        hasReviews ? "Done" : canReview ? "Pending" : "Not available"));
    }

    private static NavItem item(String key, String label, String url,
                                boolean enabled, String currentKey,
                                String status) {
        return new NavItem(key, label, url, status, enabled,
                key.equals(currentKey));
    }

    /** Provenance label — keeps the five source categories distinct. */
    public static String provenanceLabel(EvidenceProvenance provenance) {
        return provenance == EvidenceProvenance.CUSTOMER_INTAKE
                ? "Customer Reported"
                : "Observed by Tool";
    }

    /** Tool status rendered as explicit text, never colour alone. */
    public static String toolStatusLabel(ToolStatus status) {
        return switch (status) {
            case SUCCESS -> "Success";
            case NOT_FOUND -> "Not found";
            case UNAVAILABLE -> "Unavailable";
            case TIMEOUT -> "Timeout";
            case INVALID_REQUEST -> "Invalid request";
        };
    }

    /** 0.86 -> 86 for the confidence display. */
    public static int confidencePercent(double confidence) {
        return (int) Math.round(confidence * 100.0);
    }
}
