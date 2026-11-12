package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.rag.PolicyRetrievalOutcome;
import com.example.fraudcase.rag.PolicyRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Grounding-outage semantics end-to-end: when retrieval finds nothing
 * (NO_MATCH) or fails (UNAVAILABLE) the system must abstain — a confident
 * model output is rejected and the deterministic fallback's R005 rule
 * forces MANUAL_REVIEW; a correctly abstaining model persists safely.
 */
@SpringBootTest(properties = "capstone.processor-mode=spring-ai")
@AutoConfigureMockMvc
class SpringAiRagNoMatchTest {

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubChatModel stub;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private PolicyCitationRepository citationRepository;

    @Autowired
    private AuditEventAppender auditAppender;

    @MockitoBean
    private PolicyRetriever policyRetriever;

    @BeforeEach
    void setUp() {
        stub.reset();
        Mockito.when(policyRetriever.retrievePolicyEvidence(any()))
                .thenReturn(PolicyRetrievalOutcome.noMatch("sha256:test", 5));
    }

    private List<AuditEventType> auditTypes(String caseId) {
        return auditAppender.findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
    }

    private String createCase() {
        return fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
    }

    @Test
    void noMatchOverCommittingModelIsRejectedAndR005RoutesToManualReview()
            throws Exception {
        String caseId = createCase();
        stub.enqueueJson("""
                {"caseId":null,"classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"confident fraud despite no policy context"}
                """);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.recommendedRoute").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.ruleIds").value(
                        org.hamcrest.Matchers.hasItem("R005")));

        RecommendationEntity rec =
                investigationService.latestRecommendation(caseId);
        assertThat(rec.getRuleIds()).contains("R005");
        assertThat(citationRepository
                .findByRecommendationId(rec.getRecommendationId())).isEmpty();
        assertThat(auditTypes(caseId)).contains(
                AuditEventType.POLICY_RETRIEVAL_NO_MATCH,
                AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void noMatchAbstainingModelPersistsSafely() throws Exception {
        String caseId = createCase();
        stub.enqueueJson("""
                {"caseId":null,"classification":"INSUFFICIENT_EVIDENCE",
                 "riskLevel":"MEDIUM","recommendedRoute":"MANUAL_REVIEW",
                 "confidence":0.3,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":["no applicable policy"],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"Insufficient evidence; abstaining."}
                """);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SPRING_AI"))
                .andExpect(jsonPath("$.recommendedRoute").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.classification").value("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void retrievalFailureOverCommittingModelFallsBackToR005() throws Exception {
        Mockito.when(policyRetriever.retrievePolicyEvidence(any()))
                .thenReturn(PolicyRetrievalOutcome.unavailable("store outage"));
        String caseId = createCase();
        stub.enqueueJson("""
                {"caseId":null,"classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"confident despite outage"}
                """);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recommendedRoute").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.ruleIds").value(
                        org.hamcrest.Matchers.hasItem("R005")));

        assertThat(auditTypes(caseId)).contains(
                AuditEventType.POLICY_RETRIEVAL_FAILED);
    }
}
