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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HR09 — authorization boundary. With the synthetic identity seam resolved
 * to a non-REVIEWER role, disposition submissions are forbidden: no review,
 * no transition, no successful-review audit — only a denial event.
 */
@SpringBootTest(properties = {
        "capstone.processor-mode=rules",
        "capstone.review.actor-role=ANALYST"})
@AutoConfigureMockMvc
class ReviewAuthorizationTest {

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
    void hr09_nonReviewerIdentityIsForbidden() throws Exception {
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        investigationService.investigate(caseId);
        String recId = investigationService.latestRecommendation(caseId)
                .getRecommendationId();

        mockMvc.perform(post("/api/cases/{id}/reviews", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"ESCALATE","reason":"analyst attempt",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "recommendationId":"%s"}
                                """.formatted(recId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REVIEW_FORBIDDEN"));

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
