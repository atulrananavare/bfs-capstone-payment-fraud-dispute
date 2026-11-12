package com.example.fraudcase.ui;

import com.example.fraudcase.api.CaseMessageRequest;
import com.example.fraudcase.api.ReviewRequest;
import com.example.fraudcase.application.CaseMessageService;
import com.example.fraudcase.application.ConversationUnavailableException;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationNotPermittedException;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.application.RecommendationNotFoundException;
import com.example.fraudcase.application.RecommendationValidationException;
import com.example.fraudcase.application.ReviewAuthorizationException;
import com.example.fraudcase.application.ReviewConflictException;
import com.example.fraudcase.application.ReviewNotPermittedException;
import com.example.fraudcase.application.ReviewService;
import com.example.fraudcase.application.ReviewValidationException;
import com.example.fraudcase.application.ReviewerIdentityProvider;
import com.example.fraudcase.application.StaleRecommendationException;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.PolicyContextStatus;
import com.example.fraudcase.domain.RecommendedRoute;
import com.example.fraudcase.domain.ReviewDispositionType;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.EvidenceItemEntity;
import com.example.fraudcase.persistence.PolicyCitationEntity;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.persistence.RecommendationRepository;
import com.example.fraudcase.persistence.ReviewDispositionEntity;
import com.example.fraudcase.persistence.ToolTraceEntity;
import com.example.fraudcase.rag.PolicyQueryBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Screens 2–6 of the analyst workbench plus the compact case-scoped
 * follow-up. Thin MVC controller: every read comes from the verified
 * services/repositories and every write goes through the same application
 * services the JSON API uses — no business rules are duplicated here.
 */
@Controller
@RequestMapping("/ui/cases/{caseId}")
public class CaseUiController {

    private final FraudCaseService fraudCaseService;
    private final InvestigationService investigationService;
    private final ReviewService reviewService;
    private final CaseMessageService messageService;
    private final ReviewerIdentityProvider identityProvider;
    private final RecommendationRepository recommendationRepository;
    private final PolicyCitationRepository citationRepository;
    private final AuditEventAppender auditAppender;
    private final CapstoneProperties properties;

    public CaseUiController(FraudCaseService fraudCaseService,
                            InvestigationService investigationService,
                            ReviewService reviewService,
                            CaseMessageService messageService,
                            ReviewerIdentityProvider identityProvider,
                            RecommendationRepository recommendationRepository,
                            PolicyCitationRepository citationRepository,
                            AuditEventAppender auditAppender,
                            CapstoneProperties properties) {
        this.fraudCaseService = fraudCaseService;
        this.investigationService = investigationService;
        this.reviewService = reviewService;
        this.messageService = messageService;
        this.identityProvider = identityProvider;
        this.recommendationRepository = recommendationRepository;
        this.citationRepository = citationRepository;
        this.auditAppender = auditAppender;
        this.properties = properties;
    }

    // ---- shared shell ------------------------------------------------------

    private FraudCase base(String caseId, String step, Model model) {
        FraudCase fraudCase = fraudCaseService.getCase(caseId);
        boolean investigated = !investigationService.tracesFor(caseId).isEmpty();
        RecommendationEntity rec = latestRec(caseId);
        List<EvidenceItemEntity> evidence = investigationService
                .evidenceEntitiesFor(caseId);
        boolean canReview = fraudCase.state() == CaseState.READY_FOR_REVIEW
                || fraudCase.state() == CaseState.UNDER_REVIEW;
        boolean hasReviews = !reviewService.reviewsFor(caseId).isEmpty();
        model.addAttribute("case", fraudCase);
        model.addAttribute("processorMode", properties.getProcessorMode());
        model.addAttribute("nav", UiModels.workflowNav(caseId, step,
                investigated, rec != null, !evidence.isEmpty(),
                canReview, hasReviews));
        return fraudCase;
    }

    private RecommendationEntity latestRec(String caseId) {
        try {
            return investigationService.latestRecommendation(caseId);
        } catch (RecommendationNotFoundException ex) {
            return null;
        }
    }

    private static List<String> contradictionsOf(RecommendationEntity rec) {
        List<String> missing = rec.getMissingInformation() == null
                ? List.of() : rec.getMissingInformation();
        return missing.stream()
                .filter(m -> m.toLowerCase().contains("contradict")).toList();
    }

    // ---- case root ----------------------------------------------------------

    @GetMapping
    public String caseRoot(@PathVariable String caseId) {
        return "redirect:/ui/cases/" + caseId + "/investigation";
    }

    // ---- screen 2: investigation --------------------------------------------

    @GetMapping("/investigation")
    public String investigation(@PathVariable String caseId, Model model) {
        FraudCase fraudCase = base(caseId, "investigation", model);
        List<ToolTraceEntity> traces = investigationService.tracesFor(caseId);
        RecommendationEntity rec = latestRec(caseId);
        model.addAttribute("traces", traces);
        model.addAttribute("recommendation", rec);
        model.addAttribute("missingInformation",
                rec == null || rec.getMissingInformation() == null
                        ? List.of() : rec.getMissingInformation());
        model.addAttribute("contradictions",
                rec == null ? List.of() : contradictionsOf(rec));
        model.addAttribute("claimEvidencePresent",
                investigationService.evidenceEntitiesFor(caseId).stream()
                        .anyMatch(e -> e.getEvidenceType().name()
                                .equals("CUSTOMER_CLAIM")));
        model.addAttribute("policyStatus", policyStatus(caseId, fraudCase));
        model.addAttribute("canInvestigate",
                fraudCase.state() == CaseState.NEW
                        || fraudCase.state() == CaseState.INCOMPLETE
                        || fraudCase.state() == CaseState.ENRICHING);
        model.addAttribute("investigateLabel",
                fraudCase.state() == CaseState.NEW
                        ? "Start Investigation" : "Retry Investigation");
        return "cases/investigation";
    }

    @PostMapping("/investigate")
    public String investigate(@PathVariable String caseId,
                              RedirectAttributes redirectAttributes) {
        try {
            investigationService.investigate(caseId);
        } catch (InvestigationNotPermittedException ex) {
            redirectAttributes.addFlashAttribute("investigationError",
                    "Investigation is not permitted in the current case state.");
        } catch (RecommendationValidationException ex) {
            redirectAttributes.addFlashAttribute("investigationError",
                    "Investigation completed without a valid recommendation; "
                            + "the case requires manual follow-up.");
        }
        return "redirect:/ui/cases/" + caseId + "/investigation";
    }

    // ---- screen 3: recommendation -------------------------------------------

    @GetMapping("/recommendation")
    public String recommendation(@PathVariable String caseId, Model model) {
        FraudCase fraudCase = base(caseId, "recommendation", model);
        RecommendationEntity rec = latestRec(caseId);
        model.addAttribute("recommendation", rec);
        model.addAttribute("citations", rec == null ? List.of()
                : citationRepository.findByRecommendationId(
                        rec.getRecommendationId()));
        model.addAttribute("missingInformation",
                rec == null || rec.getMissingInformation() == null
                        ? List.of() : rec.getMissingInformation());
        model.addAttribute("contradictions",
                rec == null ? List.of() : contradictionsOf(rec));
        model.addAttribute("canReview",
                fraudCase.state() == CaseState.READY_FOR_REVIEW
                        || fraudCase.state() == CaseState.UNDER_REVIEW);
        // Compact case-scoped follow-up (Phase 5) — never the primary flow.
        boolean conversationAvailable = properties.getProcessorMode()
                == CapstoneProperties.ProcessorMode.SPRING_AI;
        model.addAttribute("conversationAvailable", conversationAvailable);
        model.addAttribute("messages", messageService.history(caseId));
        model.addAttribute("followupClientRef", "UIM-" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 20).toUpperCase());
        return "cases/recommendation";
    }

    @PostMapping("/messages")
    public String followUp(@PathVariable String caseId,
                           @RequestParam("message") String message,
                           @RequestParam("clientReference") String clientReference,
                           RedirectAttributes redirectAttributes) {
        try {
            messageService.submit(caseId,
                    new CaseMessageRequest(message, clientReference));
        } catch (ConversationUnavailableException ex) {
            redirectAttributes.addFlashAttribute("conversationError",
                    "The follow-up assistant is unavailable in rules mode.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("conversationError",
                    "The message could not be processed; it was not "
                            + "persisted.");
        }
        return "redirect:/ui/cases/" + caseId + "/recommendation#followup";
    }

    // ---- screen 4: evidence workspace ----------------------------------------

    @GetMapping("/evidence")
    public String evidence(@PathVariable String caseId, Model model) {
        FraudCase fraudCase = base(caseId, "evidence", model);
        List<EvidenceItemEntity> items = investigationService
                .evidenceEntitiesFor(caseId);
        model.addAttribute("claim", fraudCase.claim());
        model.addAttribute("evidence", items);
        return "cases/evidence";
    }

    // ---- screen 5: policy citations -------------------------------------------

    @GetMapping("/policy")
    public String policy(@PathVariable String caseId, Model model) {
        FraudCase fraudCase = base(caseId, "policy", model);
        RecommendationEntity rec = latestRec(caseId);
        PolicyContextStatus status = policyStatus(caseId, fraudCase);
        model.addAttribute("recommendation", rec);
        model.addAttribute("citations", rec == null ? List.of()
                : citationRepository.findByRecommendationId(
                        rec.getRecommendationId()));
        model.addAttribute("policyStatus", status);
        model.addAttribute("topK", properties.getRetrieval().getTopK());
        model.addAttribute("similarityThreshold",
                properties.getRetrieval().getSimilarityThreshold());
        model.addAttribute("retrievalEnabled",
                properties.getRetrieval().isEnabled());
        model.addAttribute("queryContext", PolicyQueryBuilder.queryText(
                PolicyQueryBuilder.fromContext(fraudCase, List.of())));
        return "cases/policy";
    }

    /** Open Passage — exact stored chunk, guarded to this case. */
    @GetMapping("/policy/citations/{citationId}")
    public String passage(@PathVariable String caseId,
                          @PathVariable String citationId, Model model) {
        FraudCase fraudCase = base(caseId, "policy", model);
        PolicyCitationEntity citation = citationRepository
                .findById(citationId)
                .filter(c -> recommendationRepository
                        .findById(c.getRecommendationId())
                        .map(r -> r.getCaseId().equals(caseId))
                        .orElse(false))
                .orElseThrow(() -> new com.example.fraudcase.application
                        .CaseNotFoundException("citation " + citationId
                        + " is not part of case " + caseId));
        model.addAttribute("citation", citation);
        model.addAttribute("fraudCase", fraudCase);
        return "cases/passage";
    }

    // ---- screen 6: human review -----------------------------------------------

    @GetMapping("/review")
    public String review(@PathVariable String caseId, Model model) {
        FraudCase fraudCase = base(caseId, "review", model);
        RecommendationEntity rec = latestRec(caseId);
        boolean canReview = rec != null
                && (fraudCase.state() == CaseState.READY_FOR_REVIEW
                || fraudCase.state() == CaseState.UNDER_REVIEW);
        if (!model.containsAttribute("reviewForm")) {
            ReviewForm form = new ReviewForm();
            if (rec != null) {
                form.setRecommendationId(rec.getRecommendationId());
            }
            // Per-render idempotency key: an accidental double submit
            // replays the stored disposition instead of duplicating it.
            form.setClientReference(
                    "UI-" + UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 24).toUpperCase());
            model.addAttribute("reviewForm", form);
        }
        reviewContextModel(caseId, rec, model);
        model.addAttribute("canReview", canReview);
        model.addAttribute("reviewer", identityProvider.resolve());
        model.addAttribute("dispositions", ReviewDispositionType.values());
        model.addAttribute("routes", RecommendedRoute.values());
        model.addAttribute("existingReviews",
                reviewService.reviewsFor(caseId));
        return "cases/review";
    }

    @PostMapping("/review")
    public String submitReview(@PathVariable String caseId,
                               @ModelAttribute("reviewForm") ReviewForm form,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        try {
            ReviewRequest request = form.toRequest();
            ReviewService.ReviewResult result =
                    reviewService.submit(caseId, request);
            ReviewDispositionEntity e = result.disposition();
            redirectAttributes.addFlashAttribute("reviewSuccess", true);
            redirectAttributes.addFlashAttribute("reviewResultId", e.getReviewId());
            redirectAttributes.addFlashAttribute("reviewResultDisposition",
                    e.getDisposition());
            redirectAttributes.addFlashAttribute("reviewResultReviewer",
                    e.getReviewerId());
            redirectAttributes.addFlashAttribute("reviewResultRecordedAt",
                    e.getRecordedAt());
            redirectAttributes.addFlashAttribute("reviewResultState",
                    result.resultingState());
            redirectAttributes.addFlashAttribute("reviewResultRoute",
                    e.getTargetRoute());
            redirectAttributes.addFlashAttribute("reviewResultReplay",
                    result.idempotentReplay());
            return "redirect:/ui/cases/" + caseId + "/review";
        } catch (ReviewValidationException ex) {
            model.addAttribute("reviewErrors", ex.violations());
        } catch (ReviewAuthorizationException ex) {
            model.addAttribute("reviewErrors", List.of(
                    "The resolved identity is not authorised to "
                            + "disposition cases."));
        } catch (StaleRecommendationException | ReviewConflictException ex) {
            model.addAttribute("reviewErrors", List.of(
                    "The recommendation changed after this review was "
                            + "opened. Reload the latest review context "
                            + "before submitting."));
        } catch (ReviewNotPermittedException ex) {
            model.addAttribute("reviewErrors", List.of(
                    "This case is not in a state that accepts a review "
                            + "disposition."));
        }
        FraudCase fraudCase = base(caseId, "review", model);
        RecommendationEntity rec = latestRec(caseId);
        reviewContextModel(caseId, rec, model);
        model.addAttribute("canReview", rec != null
                && (fraudCase.state() == CaseState.READY_FOR_REVIEW
                || fraudCase.state() == CaseState.UNDER_REVIEW));
        model.addAttribute("reviewer", identityProvider.resolve());
        model.addAttribute("dispositions", ReviewDispositionType.values());
        model.addAttribute("routes", RecommendedRoute.values());
        model.addAttribute("existingReviews",
                reviewService.reviewsFor(caseId));
        return "cases/review";
    }

    private void reviewContextModel(String caseId, RecommendationEntity rec,
                                    Model model) {
        model.addAttribute("recommendation", rec);
        List<String> missing = rec == null || rec.getMissingInformation() == null
                ? List.of() : rec.getMissingInformation();
        model.addAttribute("missingInformation", missing);
        model.addAttribute("contradictions",
                rec == null ? List.of() : contradictionsOf(rec));
        List<EvidenceItemEntity> evidence = rec == null ? List.of()
                : investigationService.evidenceEntitiesFor(caseId);
        List<PolicyCitationEntity> citations = rec == null ? List.of()
                : citationRepository.findByRecommendationId(
                        rec.getRecommendationId());
        model.addAttribute("evidence", evidence);
        model.addAttribute("citations", citations);
        model.addAttribute("needAckEvidence", !evidence.isEmpty());
        model.addAttribute("needAckPolicy", !citations.isEmpty());
        model.addAttribute("needAckMissing", !missing.isEmpty());
        model.addAttribute("needAckContradictions",
                missing.stream().anyMatch(
                        m -> m.toLowerCase().contains("contradict")));
    }

    /** Policy context status derived from the append-only audit trail. */
    private PolicyContextStatus policyStatus(String caseId, FraudCase fraudCase) {
        if (properties.getProcessorMode()
                == CapstoneProperties.ProcessorMode.RULES) {
            return PolicyContextStatus.NOT_REQUESTED;
        }
        List<AuditEventEntity> events =
                auditAppender.findByCaseIdOrderByRecordedAt(caseId);
        for (int i = events.size() - 1; i >= 0; i--) {
            switch (events.get(i).getEventType()) {
                case POLICY_RETRIEVAL_COMPLETED:
                    return PolicyContextStatus.RETRIEVED;
                case POLICY_RETRIEVAL_NO_MATCH:
                    return PolicyContextStatus.NO_MATCH;
                case POLICY_RETRIEVAL_FAILED:
                    return PolicyContextStatus.UNAVAILABLE;
                default:
            }
        }
        return latestRec(caseId) == null
                ? PolicyContextStatus.NOT_REQUESTED : PolicyContextStatus.NO_MATCH;
    }
}
