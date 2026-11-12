package com.example.fraudcase.api;

import com.example.fraudcase.api.InvestigationResponses.CitationResponse;
import com.example.fraudcase.api.ReviewResponses.EvidenceSummary;
import com.example.fraudcase.api.ReviewResponses.ReviewContextResponse;
import com.example.fraudcase.api.ReviewResponses.ReviewResponse;
import com.example.fraudcase.api.ReviewResponses.ReviewView;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.application.ReviewService;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.persistence.ReviewDispositionEntity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Phase 6 human-review endpoints. Dispositions record a human decision
 * only — no financial or customer-impacting action can be triggered here.
 * Reviewer identity, timestamps and case state come exclusively from the
 * application; the payload cannot supply them (unknown fields are
 * rejected).
 */
@RestController
@RequestMapping("/api/cases/{caseId}")
public class ReviewController {

    private final FraudCaseService fraudCaseService;
    private final InvestigationService investigationService;
    private final ReviewService reviewService;
    private final EvidenceItemRepository evidenceRepository;
    private final PolicyCitationRepository citationRepository;

    public ReviewController(FraudCaseService fraudCaseService,
                            InvestigationService investigationService,
                            ReviewService reviewService,
                            EvidenceItemRepository evidenceRepository,
                            PolicyCitationRepository citationRepository) {
        this.fraudCaseService = fraudCaseService;
        this.investigationService = investigationService;
        this.reviewService = reviewService;
        this.evidenceRepository = evidenceRepository;
        this.citationRepository = citationRepository;
    }

    @PostMapping("/reviews")
    public ResponseEntity<ReviewResponse> submitReview(
            @PathVariable String caseId,
            @Valid @RequestBody ReviewRequest request) {
        ReviewService.ReviewResult result = reviewService.submit(caseId, request);
        ReviewDispositionEntity entity = result.disposition();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reviewId}")
                .buildAndExpand(entity.getReviewId())
                .toUri();
        return ResponseEntity.created(location).body(new ReviewResponse(
                entity.getReviewId(), entity.getCaseId(),
                entity.getRecommendationId(), entity.getReviewerId(),
                entity.getDisposition(), entity.getTargetRoute(),
                entity.getReason(), entity.isAckEvidence(),
                entity.isAckPolicy(), entity.isAckMissingInfo(),
                entity.isAckContradictions(), result.idempotentReplay(),
                entity.getRecordedAt(), result.resultingState()));
    }

    /** Case-scoped, deterministically ordered review history. */
    @GetMapping("/reviews")
    public List<ReviewView> reviews(@PathVariable String caseId) {
        fraudCaseService.getCase(caseId); // 404-safe check
        return reviewService.reviewsFor(caseId).stream()
                .map(ReviewController::toView)
                .toList();
    }

    /**
     * Everything a reviewer must see before dispositioning: the advisory
     * recommendation, masked evidence summaries, exact policy passages,
     * missing information, contradictions and the acknowledgement
     * checklist the API enforces. Never exposes prompts, model internals
     * or another case's data.
     */
    @GetMapping("/review-context")
    public ReviewContextResponse reviewContext(@PathVariable String caseId) {
        FraudCase fraudCase = fraudCaseService.getCase(caseId);
        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        List<String> missing = rec.getMissingInformation() == null
                ? List.of() : rec.getMissingInformation();
        List<String> contradictions = missing.stream()
                .filter(m -> m.toLowerCase().contains("contradict")).toList();
        List<EvidenceSummary> evidence = evidenceRepository
                .findByCaseIdOrderByObservedAt(caseId).stream()
                .map(e -> new EvidenceSummary(e.getEvidenceId(),
                        e.getEvidenceType(), e.getSeverity(),
                        e.getValueSummary(), e.getProvenance()))
                .toList();
        List<CitationResponse> citations = citationRepository
                .findByRecommendationId(rec.getRecommendationId()).stream()
                .map(c -> new CitationResponse(
                        c.getCitationId(), c.getRecommendationId(),
                        c.getDocumentId(), c.getTitle(), c.getVersion(),
                        c.getEffectiveDate() == null
                                ? null : c.getEffectiveDate().toString(),
                        c.getSection(), c.getChunkId(), c.getScore(),
                        c.getPassage()))
                .toList();
        List<ReviewView> reviews = reviewService.reviewsFor(caseId).stream()
                .map(ReviewController::toView).toList();
        return new ReviewContextResponse(
                caseId, fraudCase.state(), rec.getRecommendationId(),
                rec.getMode(), rec.getClassification(), rec.getRisk(),
                rec.getRoute(), rec.getConfidence(),
                rec.isHumanReviewRequired(), missing, contradictions,
                evidence, citations,
                !evidence.isEmpty(), !citations.isEmpty(),
                !missing.isEmpty(), !contradictions.isEmpty(), reviews);
    }

    private static ReviewView toView(ReviewDispositionEntity e) {
        return new ReviewView(
                e.getReviewId(), e.getCaseId(), e.getRecommendationId(),
                e.getReviewerId(), e.getDisposition(), e.getTargetRoute(),
                e.getReason(), e.isAckEvidence(), e.isAckPolicy(),
                e.isAckMissingInfo(), e.isAckContradictions(),
                e.getRecordedAt());
    }
}
