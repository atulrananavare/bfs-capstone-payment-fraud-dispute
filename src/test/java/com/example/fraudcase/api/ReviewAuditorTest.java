package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.ReviewDispositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditor role: read-only access to review history and context, but no
 * authority to submit a disposition.
 */
@SpringBootTest(properties = {
        "capstone.processor-mode=rules",
        "capstone.review.actor-role=AUDITOR"})
@AutoConfigureMockMvc
class ReviewAuditorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private ReviewDispositionRepository reviewRepository;

    @Autowired
    private AuditEventAppender auditAppender;

    @Test
    void auditorReadsButCannotDisposition() throws Exception {
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        investigationService.investigate(caseId);
        String recId = investigationService.latestRecommendation(caseId)
                .getRecommendationId();

        // Read-only review history + context remain accessible.
        mockMvc.perform(get("/api/cases/{id}/reviews", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/cases/{id}/review-context", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recId));

        mockMvc.perform(post("/api/cases/{id}/reviews", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"ESCALATE","reason":"auditor attempt",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "recommendationId":"%s"}
                                """.formatted(recId)))
                .andExpect(status().isForbidden());

        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.READY_FOR_REVIEW);
        List<AuditEventType> types = auditAppender
                .findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
        assertThat(types).contains(AuditEventType.REVIEW_AUTHORIZATION_DENIED);
        assertThat(types).doesNotContain(AuditEventType.REVIEW_SUBMITTED);
    }
}
