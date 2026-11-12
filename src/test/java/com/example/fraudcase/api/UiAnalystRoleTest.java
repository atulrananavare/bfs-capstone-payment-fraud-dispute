package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.persistence.ReviewDispositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UI under a non-reviewer identity: read paths stay usable, the
 * disposition submit is blocked by the server-side authorisation gate —
 * never merely by hidden controls.
 */
@SpringBootTest(properties = {
        "capstone.processor-mode=rules",
        "capstone.review.actor-role=ANALYST"})
@AutoConfigureMockMvc
class UiAnalystRoleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private ReviewDispositionRepository reviewRepository;

    @Test
    void analystCannotSubmitDispositionViaUi() throws Exception {
        String caseId = fraudCaseService.createCase(
                TestFixtures.validRequest()).caseId();
        investigationService.investigate(caseId);
        String recId = investigationService.latestRecommendation(caseId)
                .getRecommendationId();

        // Read path stays available.
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Human Review")));

        // Write path is rejected by the application-layer authorisation
        // boundary and rendered as a safe error — no disposition, no
        // state change.
        mockMvc.perform(post("/ui/cases/{id}/review", caseId)
                        .with(csrf())
                        .param("disposition", "ESCALATE")
                        .param("reason", "analyst attempt")
                        .param("recommendationId", recId)
                        .param("acknowledgeEvidenceReviewed", "true")
                        .param("acknowledgeMissingInformation", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Review was not submitted")))
                .andExpect(content().string(containsString(
                        "not authorised")));

        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.READY_FOR_REVIEW);
    }
}
