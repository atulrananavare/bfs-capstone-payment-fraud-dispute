package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.RiskLevel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "recommendation")
public class RecommendationEntity {

    @Id
    @Column(name = "recommendation_id", length = 40)
    private String recommendationId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "investigation_id", length = 40)
    private String investigationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private RecommendationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 48)
    private CaseClassification classification;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 48)
    private RecommendedRoute route;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk", nullable = false, length = 16)
    private RiskLevel risk;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "human_review_required", nullable = false)
    private boolean humanReviewRequired;

    @Column(name = "rationale", nullable = false, length = 2000)
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recommendation_evidence",
            joinColumns = @JoinColumn(name = "recommendation_id"))
    @Column(name = "evidence_id", length = 64)
    private List<String> evidenceIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recommendation_rule_ids",
            joinColumns = @JoinColumn(name = "recommendation_id"))
    @Column(name = "rule_id", length = 16)
    private List<String> ruleIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recommendation_trace_ids",
            joinColumns = @JoinColumn(name = "recommendation_id"))
    @Column(name = "trace_id", length = 40)
    private List<String> toolTraceIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recommendation_missing_info",
            joinColumns = @JoinColumn(name = "recommendation_id"))
    @Column(name = "missing_item", length = 255)
    private List<String> missingInformation = new ArrayList<>();

    protected RecommendationEntity() {
    }

    public RecommendationEntity(String recommendationId, String caseId, String investigationId,
                                RecommendationMode mode, CaseClassification classification,
                                RecommendedRoute route, RiskLevel risk, double confidence,
                                boolean humanReviewRequired, String rationale,
                                OffsetDateTime createdAt) {
        this.recommendationId = recommendationId;
        this.caseId = caseId;
        this.investigationId = investigationId;
        this.mode = mode;
        this.classification = classification;
        this.route = route;
        this.risk = risk;
        this.confidence = confidence;
        this.humanReviewRequired = humanReviewRequired;
        this.rationale = rationale;
        this.createdAt = createdAt;
    }

    public String getRecommendationId() {
        return recommendationId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public RecommendationMode getMode() {
        return mode;
    }

    public CaseClassification getClassification() {
        return classification;
    }

    public RecommendedRoute getRoute() {
        return route;
    }

    public RiskLevel getRisk() {
        return risk;
    }

    public double getConfidence() {
        return confidence;
    }

    public boolean isHumanReviewRequired() {
        return humanReviewRequired;
    }

    public String getRationale() {
        return rationale;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<String> getEvidenceIds() {
        return evidenceIds;
    }

    public void setEvidenceIds(List<String> evidenceIds) {
        this.evidenceIds = evidenceIds;
    }

    public List<String> getRuleIds() {
        return ruleIds;
    }

    public void setRuleIds(List<String> ruleIds) {
        this.ruleIds = ruleIds;
    }

    public List<String> getToolTraceIds() {
        return toolTraceIds;
    }

    public void setToolTraceIds(List<String> toolTraceIds) {
        this.toolTraceIds = toolTraceIds;
    }

    public List<String> getMissingInformation() {
        return missingInformation;
    }

    public void setMissingInformation(List<String> missingInformation) {
        this.missingInformation = missingInformation;
    }
}
