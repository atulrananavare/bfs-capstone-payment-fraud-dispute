package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.ReviewDispositionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Append-only human review record. Dispositions are immutable through
 * ordinary application flows — a correction is a new disposition on a new
 * recommendation, never an update. No financial action is ever implied by
 * a row here.
 */
@Entity
@Table(name = "review_disposition")
public class ReviewDispositionEntity {

    @Id
    @Column(name = "review_id", length = 40)
    private String reviewId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "recommendation_id", nullable = false, length = 40)
    private String recommendationId;

    @Column(name = "reviewer_id", nullable = false, length = 64)
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false, length = 32)
    private ReviewDispositionType disposition;

    @Column(name = "reason", nullable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_route", length = 40)
    private RecommendedRoute targetRoute;

    @Column(name = "ack_evidence", nullable = false)
    private boolean ackEvidence;

    @Column(name = "ack_policy", nullable = false)
    private boolean ackPolicy;

    @Column(name = "ack_missing_info", nullable = false)
    private boolean ackMissingInfo;

    @Column(name = "ack_contradictions", nullable = false)
    private boolean ackContradictions;

    @Column(name = "client_reference", length = 128)
    private String clientReference;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected ReviewDispositionEntity() {
    }

    public ReviewDispositionEntity(String reviewId, String caseId,
                                   String recommendationId, String reviewerId,
                                   ReviewDispositionType disposition, String reason,
                                   RecommendedRoute targetRoute,
                                   boolean ackEvidence, boolean ackPolicy,
                                   boolean ackMissingInfo, boolean ackContradictions,
                                   String clientReference, OffsetDateTime recordedAt) {
        this.reviewId = reviewId;
        this.caseId = caseId;
        this.recommendationId = recommendationId;
        this.reviewerId = reviewerId;
        this.disposition = disposition;
        this.reason = reason;
        this.targetRoute = targetRoute;
        this.ackEvidence = ackEvidence;
        this.ackPolicy = ackPolicy;
        this.ackMissingInfo = ackMissingInfo;
        this.ackContradictions = ackContradictions;
        this.clientReference = clientReference;
        this.recordedAt = recordedAt;
    }

    /** Dispositions are append-only; mutation is a persistence-layer bug. */
    @PreUpdate
    void rejectUpdate() {
        throw new IllegalStateException(
                "review_disposition rows are immutable; append a new disposition");
    }

    @PreRemove
    void rejectDelete() {
        throw new IllegalStateException(
                "review_disposition rows are immutable; history is never deleted");
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getRecommendationId() {
        return recommendationId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public ReviewDispositionType getDisposition() {
        return disposition;
    }

    public String getReason() {
        return reason;
    }

    public RecommendedRoute getTargetRoute() {
        return targetRoute;
    }

    public boolean isAckEvidence() {
        return ackEvidence;
    }

    public boolean isAckPolicy() {
        return ackPolicy;
    }

    public boolean isAckMissingInfo() {
        return ackMissingInfo;
    }

    public boolean isAckContradictions() {
        return ackContradictions;
    }

    public String getClientReference() {
        return clientReference;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }
}
