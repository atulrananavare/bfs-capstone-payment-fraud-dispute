package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.rag.PolicyRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outage resilience: human review relies on persisted case, evidence,
 * recommendation and citation data — not on live vector-store retrieval or
 * conversation memory. Both are mocked to fail; the review workflow must
 * still function without fabricating anything.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
@AutoConfigureMockMvc
class ReviewOutageTest {

    @MockitoBean
    private PolicyRetriever policyRetriever;

    @MockitoBean
    private ChatMemory chatMemory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    private String[] investigatedCase() {
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        investigationService.investigate(caseId);
        return new String[]{caseId, investigationService
                .latestRecommendation(caseId).getRecommendationId()};
    }

    @Test
    void vectorStoreAndMemoryOutageDoNotBlockReview() throws Exception {
        when(policyRetriever.retrievePolicyEvidence(any()))
                .thenThrow(new IllegalStateException("vector store down"));
        when(chatMemory.get(any()))
                .thenThrow(new IllegalStateException("memory store down"));

        var ids = investigatedCase();

        mockMvc.perform(get("/api/cases/{id}/review-context", ids[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(ids[1]))
                .andExpect(jsonPath("$.evidence").isArray())
                .andExpect(jsonPath("$.missingInformation").isArray());

        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"ESCALATE",
                                 "reason":"persisted review context is sufficient",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "recommendationId":"%s"}
                                """.formatted(ids[1])))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingCaseStatus").value("ROUTED"));
    }
}
