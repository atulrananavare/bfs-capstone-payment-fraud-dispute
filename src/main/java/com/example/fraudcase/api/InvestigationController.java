package com.example.fraudcase.api;

import com.example.fraudcase.api.InvestigationResponses.EvidenceItemResponse;
import com.example.fraudcase.api.InvestigationResponses.InvestigationSummaryResponse;
import com.example.fraudcase.api.InvestigationResponses.RecommendationResponse;
import com.example.fraudcase.api.InvestigationResponses.ToolTraceResponse;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.FraudCaseRecommendation;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Phase 2 deterministic-investigation endpoints. No AI calls occur on any
 * of these paths.
 */
@RestController
@RequestMapping("/api/cases/{caseId}")
public class InvestigationController {

    private final FraudCaseService fraudCaseService;
    private final InvestigationService investigationService;
    private final PolicyCitationRepository citationRepository;

    public InvestigationController(FraudCaseService fraudCaseService,
                                   InvestigationService investigationService,
                                   PolicyCitationRepository citationRepository) {
        this.fraudCaseService = fraudCaseService;
        this.investigationService = investigationService;
        this.citationRepository = citationRepository;
    }

    @PostMapping("/investigations")
    public ResponseEntity<InvestigationSummaryResponse> investigate(@PathVariable String caseId) {
        fraudCaseService.getCase(caseId); // 404-safe check before processing
        InvestigationService.InvestigationResult result = investigationService.investigate(caseId);
        FraudCaseRecommendation rec = result.recommendation();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{investigationId}")
                .buildAndExpand(result.investigationId())
                .toUri();
        return ResponseEntity.created(location).body(new InvestigationSummaryResponse(
                result.investigationId(),
                caseId,
                result.fraudCase().state(),
                result.toolsExecuted(),
                result.evidenceCount(),
                latestRecommendationId(caseId),
                result.mode(),
                result.mode() == RecommendationMode.RULES_FALLBACK,
                rec.classification(),
                rec.riskLevel(),
                rec.recommendedRoute(),
                rec.confidence(),
                rec.humanReviewRequired(),
                rec.missingInformation(),
                rec.ruleIds()));
    }

    @GetMapping("/evidence")
    public List<EvidenceItemResponse> evidence(@PathVariable String caseId) {
        fraudCaseService.getCase(caseId);
        return investigationService.evidenceEntitiesFor(caseId).stream()
                .map(e -> new EvidenceItemResponse(
                        e.getEvidenceId(), e.getEvidenceType(), e.getSeverity(),
                        e.getValueSummary(), e.getProvenance(), e.getObservedAt(),
                        e.getInvestigationId()))
                .toList();
    }

    @GetMapping("/tool-traces")
    public List<ToolTraceResponse> toolTraces(@PathVariable String caseId) {
        fraudCaseService.getCase(caseId);
        return investigationService.tracesFor(caseId).stream()
                .map(t -> new ToolTraceResponse(
                        t.getTraceId(), t.getInvestigationId(), t.getToolName(),
                        t.getStatus(), t.getDurationMs(), t.getRequestHash(),
                        t.getResultSummary(), t.getExecutedAt()))
                .toList();
    }

    @GetMapping("/recommendation")
    public RecommendationResponse recommendation(@PathVariable String caseId) {
        fraudCaseService.getCase(caseId);
        RecommendationEntity entity = investigationService.latestRecommendation(caseId);
        var citations = citationRepository.findByRecommendationId(entity.getRecommendationId())
                .stream()
                .map(c -> new com.example.fraudcase.domain.PolicyCitation(
                        c.getDocumentId(), c.getVersion(), c.getSection(),
                        c.getChunkId(), c.getScore()))
                .toList();
        return new RecommendationResponse(
                entity.getRecommendationId(), entity.getCaseId(),
                entity.getInvestigationId(), entity.getMode(),
                entity.getClassification(), entity.getRisk(), entity.getRoute(),
                entity.getConfidence(), entity.isHumanReviewRequired(),
                entity.getEvidenceIds(), citations, entity.getMissingInformation(),
                entity.getRuleIds(), entity.getToolTraceIds(), entity.getRationale());
    }

    private String latestRecommendationId(String caseId) {
        return investigationService.latestRecommendation(caseId).getRecommendationId();
    }
}
