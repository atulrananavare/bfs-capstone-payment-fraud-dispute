package com.example.fraudcase.api;

import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import com.example.fraudcase.domain.ToolStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Safe API response projections for the deterministic investigation flow.
 */
public final class InvestigationResponses {

    private InvestigationResponses() {
    }

    public record InvestigationSummaryResponse(
            String investigationId,
            String caseId,
            CaseState state,
            int toolsExecuted,
            int evidenceCount,
            String recommendationId,
            RecommendationMode mode,
            boolean fallbackUsed,
            CaseClassification classification,
            RiskLevel riskLevel,
            RecommendedRoute recommendedRoute,
            double confidence,
            boolean humanReviewRequired,
            List<String> missingInformation,
            List<String> ruleIds) {
    }

    public record EvidenceItemResponse(
            String evidenceId,
            EvidenceType type,
            EvidenceSeverity severity,
            String valueSummary,
            EvidenceProvenance provenance,
            OffsetDateTime observedAt,
            String investigationId) {
    }

    public record ToolTraceResponse(
            String traceId,
            String investigationId,
            String toolName,
            ToolStatus status,
            long durationMs,
            String requestHash,
            String resultSummary,
            OffsetDateTime executedAt) {
    }

    public record RecommendationResponse(
            String recommendationId,
            String caseId,
            String investigationId,
            RecommendationMode mode,
            CaseClassification classification,
            RiskLevel riskLevel,
            RecommendedRoute recommendedRoute,
            double confidence,
            boolean humanReviewRequired,
            List<String> evidenceIds,
            List<PolicyCitation> policyCitations,
            List<String> missingInformation,
            List<String> ruleIds,
            List<String> toolTraceIds,
            String rationale) {
    }
}
