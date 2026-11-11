package com.example.fraudcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Prepared for the human-review workflow phase.
 */
@Entity
@Table(name = "review_disposition")
public class ReviewDispositionEntity {

    @Id
    @Column(name = "review_id", length = 40)
    private String reviewId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "reviewer_id", nullable = false, length = 64)
    private String reviewerId;

    @Column(name = "disposition", nullable = false, length = 32)
    private String disposition;

    @Column(name = "reason", nullable = false, length = 2000)
    private String reason;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected ReviewDispositionEntity() {
    }

    public ReviewDispositionEntity(String reviewId, String caseId, String reviewerId,
                                   String disposition, String reason,
                                   OffsetDateTime recordedAt) {
        this.reviewId = reviewId;
        this.caseId = caseId;
        this.reviewerId = reviewerId;
        this.disposition = disposition;
        this.reason = reason;
        this.recordedAt = recordedAt;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public String getDisposition() {
        return disposition;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }
}
