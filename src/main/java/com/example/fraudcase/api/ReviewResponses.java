package com.example.fraudcase.api;

import com.example.fraudcase.api.InvestigationResponses.CitationResponse;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.ReviewDispositionType;
import com.example.fraudcase.domain.RiskLevel;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase 6 review projections. Only masked/synthetic values are returned —
 * no prompts, chain-of-thought, credentials or cross-case data.
 */
public final class ReviewResponses {

    private ReviewResponses() {
    }

    /** Result of {@code POST /api/cases/{caseId}/reviews}. */
    public record ReviewResponse(
            String reviewId,
            String caseId,
            String recommendationId,
            String reviewerId,
            ReviewDispositionType disposition,
            RecommendedRoute targetRoute,
            String reason,
            boolean evidenceAcknowledged,
            boolean policyAcknowledged,
            boolean missingInformationAcknowledged,
            boolean contradictionsAcknowledged,
            boolean idempotentReplay,
            OffsetDateTime recordedAt,
            CaseState resultingCaseStatus) {
    }

    /** One row of {@code GET /api/cases/{caseId}/reviews}. */
    public record ReviewView(
            String reviewId,
            String caseId,
            String recommendationId,
            String reviewerId,
            ReviewDispositionType disposition,
            RecommendedRoute targetRoute,
            String reason,
            boolean evidenceAcknowledged,
            boolean policyAcknowledged,
            boolean missingInformationAcknowledged,
            boolean contradictionsAcknowledged,
            OffsetDateTime recordedAt) {
    }

    /** Compact evidence row for the review context (masked summary only). */
    public record EvidenceSummary(
            String evidenceId,
            EvidenceType evidenceType,
            EvidenceSeverity severity,
            String valueSummary,
            EvidenceProvenance provenance) {
    }

    /**
     * Everything an authorised reviewer needs to see before disposition —
     * the validated recommendation (advisory), evidence summaries, exact
     * policy passages, missing information and contradictions — plus the
     * acknowledgement checklist the API will enforce.
     */
    public record ReviewContextResponse(
            String caseId,
            CaseState caseState,
            String recommendationId,
            RecommendationMode recommendationMode,
            CaseClassification classification,
            RiskLevel riskLevel,
            RecommendedRoute recommendedRoute,
            double confidence,
            boolean humanReviewRequired,
            List<String> missingInformation,
            List<String> contradictions,
            List<EvidenceSummary> evidence,
            List<CitationResponse> policyCitations,
            boolean evidenceAcknowledgementRequired,
            boolean policyAcknowledgementRequired,
            boolean missingInformationAcknowledgementRequired,
            boolean contradictionAcknowledgementRequired,
            List<ReviewView> existingReviews) {
    }
}
