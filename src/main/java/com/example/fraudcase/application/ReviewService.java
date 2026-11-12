package com.example.fraudcase.application;

import com.example.fraudcase.api.ReviewRequest;
import com.example.fraudcase.domain.ActorType;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.ReviewDispositionType;
import com.example.fraudcase.domain.ReviewerPrincipal;
import com.example.fraudcase.observability.CaseMetrics;
import com.example.fraudcase.observability.CorrelationIdFilter;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.FraudCaseStore;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.persistence.RecommendationRepository;
import com.example.fraudcase.persistence.ReviewDispositionEntity;
import com.example.fraudcase.persistence.ReviewDispositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mandatory human-review workflow (Phase 6).
 *
 * <p>Flow for {@code POST /api/cases/{caseId}/reviews}:
 * <pre>
 *   resolve case (404)
 *   -> resolve trusted reviewer principal; REVIEWER role required (403,
 *      audited as REVIEW_AUTHORIZATION_DENIED)
 *   -> idempotent replay on (case, client_reference)
 *   -> latest validated recommendation (404 when none)
 *   -> recommendationId must equal the current one (409 stale/foreign)
 *   -> case must be READY_FOR_REVIEW (409 otherwise; CLOSED is terminal)
 *   -> disposition rules + acknowledgement checklist (400 on violation,
 *      audited as REVIEW_REJECTED)
 *   -> application-controlled transition READY_FOR_REVIEW -> UNDER_REVIEW
 *      -> ROUTED | CLOSED | INCOMPLETE
 *   -> persist immutable ReviewDisposition + case state + audit
 * </pre>
 *
 * <p>Exactly one disposition may exist per recommendation (unique key);
 * optimistic locking on the case prevents two concurrent reviewers from
 * both winning a transition. The review never mutates the recommendation,
 * never fabricates evidence or citations and never executes a banking
 * action — ESCALATE/REROUTE/OVERRIDE record routing, CLOSE records
 * closure, nothing more.
 */
@Service
public class ReviewService {

    private static final int AUDIT_DETAIL_MAX = 200;

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final FraudCaseStore caseStore;
    private final RecommendationRepository recommendationRepository;
    private final ReviewDispositionRepository reviewRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final PolicyCitationRepository citationRepository;
    private final ReviewerIdentityProvider identityProvider;
    private final AuditService auditService;
    private final Clock clock;
    private final CaseMetrics metrics;

    public ReviewService(FraudCaseStore caseStore,
                         RecommendationRepository recommendationRepository,
                         ReviewDispositionRepository reviewRepository,
                         EvidenceItemRepository evidenceRepository,
                         PolicyCitationRepository citationRepository,
                         ReviewerIdentityProvider identityProvider,
                         AuditService auditService,
                         Clock clock,
                         CaseMetrics metrics) {
        this.caseStore = caseStore;
        this.recommendationRepository = recommendationRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.citationRepository = citationRepository;
        this.identityProvider = identityProvider;
        this.auditService = auditService;
        this.clock = clock;
        this.metrics = metrics;
    }

    public record ReviewResult(ReviewDispositionEntity disposition,
                               CaseState resultingState,
                               boolean idempotentReplay) {
    }

    // Rejection events commit so the audit trail shows denied/rejected
    // attempts; no review row or state change exists on those paths.
    @Transactional(noRollbackFor = {
            ReviewAuthorizationException.class,
            ReviewNotPermittedException.class,
            StaleRecommendationException.class,
            ReviewValidationException.class})
    public ReviewResult submit(String caseId, ReviewRequest request) {
        MDC.put(CorrelationIdFilter.MDC_CASE_ID, caseId);
        try {
            return doSubmit(caseId, request);
        } catch (RuntimeException ex) {
            String reason = ex instanceof ReviewAuthorizationException ? "forbidden"
                    : ex instanceof StaleRecommendationException ? "stale"
                    : ex instanceof ReviewNotPermittedException ? "not_permitted"
                    : ex instanceof ReviewValidationException ? "validation"
                    : ex instanceof ReviewConflictException ? "conflict"
                    : ex instanceof CaseNotFoundException
                    || ex instanceof RecommendationNotFoundException ? "not_found"
                    : "unexpected";
            metrics.reviewRejected(reason);
            log.warn("REVIEW_REJECTED caseId={} reason={} type={}",
                    caseId, reason, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_CASE_ID);
        }
    }

    private ReviewResult doSubmit(String caseId, ReviewRequest request) {
        FraudCase fraudCase = caseStore.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        ReviewerPrincipal principal = identityProvider.resolve();
        if (!principal.mayDisposition()) {
            auditService.record(caseId, AuditEventType.REVIEW_AUTHORIZATION_DENIED,
                    ActorType.ANALYST, bounded(principal.reviewerId()
                            + "|role=" + principal.role()));
            throw new ReviewAuthorizationException(
                    "the resolved identity is not authorised to disposition cases");
        }

        // Idempotent retry: same (case, client_reference) replays the stored
        // disposition — no second record, transition or audit event.
        if (request.clientReference() != null) {
            var prior = reviewRepository.findByCaseIdAndClientReference(
                    caseId, request.clientReference());
            if (prior.isPresent()) {
                return new ReviewResult(prior.get(), fraudCase.state(), true);
            }
        }

        RecommendationEntity recommendation = recommendationRepository
                .findTopByCaseIdOrderByCreatedAtDesc(caseId)
                .orElseThrow(() -> new RecommendationNotFoundException(caseId));

        // The review must identify the recommendation the human actually
        // saw — a stale or foreign id is a conflict, never silently applied.
        if (!recommendation.getRecommendationId().equals(request.recommendationId())) {
            auditService.record(caseId, AuditEventType.REVIEW_REJECTED,
                    ActorType.REVIEWER, bounded("stale-or-foreign recommendationId"));
            throw new StaleRecommendationException(
                    "recommendationId does not identify this case's current "
                            + "recommendation; reload the review context");
        }

        if (fraudCase.state() != CaseState.READY_FOR_REVIEW
                && fraudCase.state() != CaseState.UNDER_REVIEW) {
            auditService.record(caseId, AuditEventType.REVIEW_REJECTED,
                    ActorType.REVIEWER, bounded("state=" + fraudCase.state()));
            throw new ReviewNotPermittedException(
                    "case state " + fraudCase.state()
                            + " does not allow a review disposition");
        }

        List<String> missing = recommendation.getMissingInformation() == null
                ? List.of() : recommendation.getMissingInformation();
        List<String> contradictions = missing.stream()
                .filter(m -> m.toLowerCase().contains("contradict")).toList();
        boolean hasEvidence = !evidenceRepository
                .findByCaseIdOrderByObservedAt(caseId).isEmpty();
        boolean hasCitations = !citationRepository
                .findByRecommendationId(recommendation.getRecommendationId()).isEmpty();

        validate(request, recommendation, missing, contradictions,
                hasEvidence, hasCitations);

        CaseState target = switch (request.disposition()) {
            case ESCALATE, REROUTE, OVERRIDE -> CaseState.ROUTED;
            case REQUEST_MORE_INFORMATION -> CaseState.INCOMPLETE;
            case CLOSE -> CaseState.CLOSED;
        };

        try {
            // Application-owned transition chain. UNDER_REVIEW is entered
            // and left within this transaction — it is a transient marker,
            // never a resting state.
            if (fraudCase.state() == CaseState.READY_FOR_REVIEW) {
                fraudCase.transitionTo(CaseState.UNDER_REVIEW);
                auditService.record(caseId, AuditEventType.REVIEW_STARTED,
                        ActorType.REVIEWER,
                        bounded(recommendation.getRecommendationId()));
            }
            fraudCase.transitionTo(target);

            ReviewDispositionEntity entity = new ReviewDispositionEntity(
                    "REV-" + UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 16).toUpperCase(),
                    caseId,
                    recommendation.getRecommendationId(),
                    principal.reviewerId(),
                    request.disposition(),
                    request.reason().trim(),
                    request.targetRoute(),
                    request.ackEvidence(), request.ackPolicy(),
                    request.ackMissing(), request.ackContradictions(),
                    request.clientReference(),
                    OffsetDateTime.now(clock));
            // Flush inside the transaction so optimistic-lock and unique
            // violations surface here as a controlled conflict.
            reviewRepository.saveAndFlush(entity);
            caseStore.save(fraudCase);

            auditService.record(caseId, AuditEventType.REVIEW_SUBMITTED,
                    ActorType.REVIEWER, bounded(entity.getReviewId()
                            + "|" + request.disposition()
                            + "|route=" + (request.targetRoute() == null
                            ? recommendation.getRoute() : request.targetRoute())));
            auditService.record(caseId, AuditEventType.CASE_STATE_CHANGED,
                    ActorType.REVIEWER, target.name());
            metrics.reviewSubmitted(request.disposition().name());
            log.info("REVIEW_SUBMITTED caseId={} disposition={} resultingState={}",
                    caseId, request.disposition(), target);
            return new ReviewResult(entity, target, false);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException ex) {
            throw new ReviewConflictException(
                    "a concurrent or duplicate review already resolved this "
                            + "recommendation; reload the review context");
        }
    }

    private void validate(ReviewRequest request, RecommendationEntity rec,
                          List<String> missing, List<String> contradictions,
                          boolean hasEvidence, boolean hasCitations) {
        List<String> violations = new ArrayList<>();
        if (request.reason() == null || request.reason().isBlank()) {
            violations.add("reason is required for every disposition");
        }

        boolean routingDisposition = request.disposition()
                == ReviewDispositionType.REROUTE
                || request.disposition() == ReviewDispositionType.OVERRIDE;
        if (routingDisposition && request.targetRoute() == null) {
            violations.add("targetRoute is required for "
                    + request.disposition());
        }
        if (!routingDisposition && request.targetRoute() != null) {
            violations.add("targetRoute is only allowed for REROUTE or OVERRIDE");
        }
        if (request.disposition() == ReviewDispositionType.REROUTE
                && request.targetRoute() != null
                && request.targetRoute() == rec.getRoute()) {
            violations.add("REROUTE must select a route different from the "
                    + "recommended route");
        }

        boolean finalDisposition = request.disposition()
                != ReviewDispositionType.REQUEST_MORE_INFORMATION;
        if (finalDisposition) {
            if (hasEvidence && !request.ackEvidence()) {
                violations.add("evidence review must be acknowledged before "
                        + "a final disposition");
            }
            if (hasCitations && !request.ackPolicy()) {
                violations.add("policy review must be acknowledged before a "
                        + "final disposition");
            }
            if (!missing.isEmpty() && !request.ackMissing()) {
                violations.add("missing information must be acknowledged "
                        + "before a final disposition");
            }
            if (!contradictions.isEmpty() && !request.ackContradictions()) {
                violations.add("contradictory evidence must be acknowledged "
                        + "before a final disposition");
            }
        }
        if (!violations.isEmpty()) {
            auditService.record(rec.getCaseId(),
                    AuditEventType.REVIEW_REJECTED, ActorType.REVIEWER,
                    bounded(String.join("; ", violations)));
            throw new ReviewValidationException(violations);
        }
    }

    @Transactional(readOnly = true)
    public List<ReviewDispositionEntity> reviewsFor(String caseId) {
        return reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId);
    }

    private static String bounded(String text) {
        return text == null ? ""
                : text.length() <= AUDIT_DETAIL_MAX
                ? text : text.substring(0, AUDIT_DETAIL_MAX);
    }
}
