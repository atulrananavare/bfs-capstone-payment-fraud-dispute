package com.example.fraudcase.ui;

import com.example.fraudcase.api.ReviewRequest;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.ReviewDispositionType;

/**
 * Form bean for the Human Review screen. Carries only legitimate
 * reviewer input — reviewer identity, timestamps and case state are
 * never bound from the browser. Validation against the Phase 6 rules
 * (reason, routing constraints, acknowledgements, stale recommendation)
 * stays in {@code ReviewService}.
 */
public class ReviewForm {

    private ReviewDispositionType disposition;
    private String reason;
    private RecommendedRoute targetRoute;
    private boolean acknowledgeEvidenceReviewed;
    private boolean acknowledgePolicyReviewed;
    private boolean acknowledgeMissingInformation;
    private boolean acknowledgeContradictions;
    private String recommendationId;
    private String clientReference;

    public ReviewRequest toRequest() {
        return new ReviewRequest(disposition, reason, targetRoute,
                acknowledgeEvidenceReviewed, acknowledgePolicyReviewed,
                acknowledgeMissingInformation, acknowledgeContradictions,
                recommendationId, clientReference);
    }

    public ReviewDispositionType getDisposition() {
        return disposition;
    }

    public void setDisposition(ReviewDispositionType disposition) {
        this.disposition = disposition;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public RecommendedRoute getTargetRoute() {
        return targetRoute;
    }

    public void setTargetRoute(RecommendedRoute targetRoute) {
        this.targetRoute = targetRoute;
    }

    public boolean isAcknowledgeEvidenceReviewed() {
        return acknowledgeEvidenceReviewed;
    }

    public void setAcknowledgeEvidenceReviewed(boolean acknowledgeEvidenceReviewed) {
        this.acknowledgeEvidenceReviewed = acknowledgeEvidenceReviewed;
    }

    public boolean isAcknowledgePolicyReviewed() {
        return acknowledgePolicyReviewed;
    }

    public void setAcknowledgePolicyReviewed(boolean acknowledgePolicyReviewed) {
        this.acknowledgePolicyReviewed = acknowledgePolicyReviewed;
    }

    public boolean isAcknowledgeMissingInformation() {
        return acknowledgeMissingInformation;
    }

    public void setAcknowledgeMissingInformation(boolean acknowledgeMissingInformation) {
        this.acknowledgeMissingInformation = acknowledgeMissingInformation;
    }

    public boolean isAcknowledgeContradictions() {
        return acknowledgeContradictions;
    }

    public void setAcknowledgeContradictions(boolean acknowledgeContradictions) {
        this.acknowledgeContradictions = acknowledgeContradictions;
    }

    public String getRecommendationId() {
        return recommendationId;
    }

    public void setRecommendationId(String recommendationId) {
        this.recommendationId = recommendationId;
    }

    public String getClientReference() {
        return clientReference;
    }

    public void setClientReference(String clientReference) {
        this.clientReference = clientReference;
    }
}
