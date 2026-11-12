package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.rag.PolicyRetrievalOutcome;
import com.example.fraudcase.rag.PolicyRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UI abstention state: when retrieval returns a real NO_MATCH the policy
 * screen shows the abstention banner and no fabricated citation. Same
 * mocked-retriever approach as {@code SpringAiRagNoMatchTest}.
 */
@SpringBootTest(properties = "capstone.processor-mode=spring-ai")
@AutoConfigureMockMvc
class UiPolicyAbstentionTest {

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

    @MockitoBean
    private PolicyRetriever policyRetriever;

    @BeforeEach
    void setUp() {
        stub.reset();
        when(policyRetriever.retrievePolicyEvidence(any()))
                .thenReturn(PolicyRetrievalOutcome.noMatch(
                        "synthetic no-match query", 0));
        stub.enqueueJson("""
                {"caseId":null,"classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.8,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"Anomalies corroborate the denial."}
                """);
    }

    @Test
    void noMatchShowsAbstentionWithoutFabrication() throws Exception {
        String caseId = fraudCaseService.createCase(
                TestFixtures.validRequest()).caseId();
        investigationService.investigate(caseId);

        mockMvc.perform(get("/ui/cases/{id}/policy", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "NO APPLICABLE POLICY FOUND")))
                .andExpect(content().string(containsString(
                        "Manual review is required")))
                .andExpect(content().string(not(containsString(
                        "Retrieved Policy"))));

        // Review checklist offers the policy-gap acknowledgement, never a
        // fake "I reviewed the policy" box.
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "gap acknowledged")));
    }
}
