package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.rag.PolicyRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules-mode independence: with {@code capstone.retrieval.enabled=false}
 * no RAG beans exist at all and the deterministic processor still produces
 * a complete investigation — no embedding model, no vector store, no
 * credentials required.
 */
@SpringBootTest(properties = {
        "capstone.processor-mode=rules",
        "capstone.retrieval.enabled=false"})
@AutoConfigureMockMvc
class RetrievalDisabledRulesModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private ObjectProvider<PolicyRetriever> retriever;

    @Test
    void rulesModeWorksWithNoRagInfrastructure() throws Exception {
        assertThat(retriever.getIfAvailable()).isNull();
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("RULES"))
                .andExpect(jsonPath("$.classification").value("UNAUTHORISED_TRANSACTION"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true));
    }
}
