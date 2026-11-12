package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.langchain4j.StubLc4jChatModel;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CaseClassification;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end langchain4j mode on the real context: H2 + Flyway + JPA +
 * InvestigationService + LangChain4jFraudCaseAgent, with
 * {@link StubLc4jChatModel} as the only "provider". No credentials, no
 * network — the same evidence, invariants and audit contract as
 * spring-ai mode.
 */
@SpringBootTest(properties = "capstone.processor-mode=langchain4j")
@AutoConfigureMockMvc
class LangChain4jModeInvestigationTest {

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        StubLc4jChatModel stubLc4jChatModel() {
            return new StubLc4jChatModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubLc4jChatModel stub;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private EvidenceItemRepository evidenceRepository;

    @Autowired
    private AuditEventAppender auditAppender;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    private String createCase() {
        return fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
    }

    private List<AuditEventType> auditTypes(String caseId) {
        return auditAppender.findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
    }

    /** Deferred script: build valid output citing this case's real evidence. */
    private void enqueueValidFor(String caseId) {
        stub.enqueueFunction(request -> {
            List<String> evidenceIds = evidenceRepository
                    .findByCaseIdOrderByObservedAt(caseId).stream()
                    .map(e -> e.getEvidenceId()).toList();
            String ids = evidenceIds.stream()
                    .map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
            String json = """
                    {"caseId":"%s","classification":"UNAUTHORISED_TRANSACTION",
                     "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                     "confidence":0.8,"humanReviewRequired":true,
                     "evidenceIds":[%s],"missingInformation":["check auth logs"],
                     "toolTraceIds":[],"policyCitations":[],
                     "rationale":"Device and location anomalies corroborate the denial."}
                    """.formatted(caseId, ids);
            return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
        });
    }

    @Test
    void validModelOutputPersistsAsLangChain4jMode() throws Exception {
        String caseId = createCase();
        enqueueValidFor(caseId);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("LANGCHAIN4J"))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.classification").value("UNAUTHORISED_TRANSACTION"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        assertThat(rec.getMode()).isEqualTo(RecommendationMode.LANGCHAIN4J);
        assertThat(rec.getRuleIds()).isEmpty();
        assertThat(rec.isHumanReviewRequired()).isTrue();
        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.MODEL_INVOCATION_STARTED,
                AuditEventType.MODEL_INVOCATION_COMPLETED,
                AuditEventType.RECOMMENDATION_CREATED);
        assertThat(auditTypes(caseId)).doesNotContain(
                AuditEventType.RULES_FALLBACK_STARTED);
    }

    @Test
    void modelToolCallExecutesAndTracesAreReal() {
        String caseId = createCase();
        stub.enqueueToolCalls("getTransactionDetails",
                "{\"transactionRef\":\"TXN-2026-784102\"}");
        enqueueValidFor(caseId);

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);
        assertThat(result.mode()).isEqualTo(RecommendationMode.LANGCHAIN4J);
        assertThat(investigationService.tracesFor(caseId).stream()
                .filter(t -> t.getToolName().equals("getTransactionDetails"))
                .count()).isEqualTo(2); // orchestrator run + model-driven run
    }

    @Test
    void providerFailureFallsBackToRulesAndAudits() {
        String caseId = createCase();
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);

        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(result.fraudCase().state()).isEqualTo(CaseState.READY_FOR_REVIEW);
        assertThat(result.recommendation().humanReviewRequired()).isTrue();
        assertThat(auditTypes(caseId)).contains(
                AuditEventType.MODEL_INVOCATION_FAILED);
    }

    @Test
    void malformedModelOutputNeverPersistsAndFallsBack() {
        String caseId = createCase();
        stub.enqueueJson("{\"classification\":\"TOTALLY_MADE_UP\"}");

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);

        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).contains(
                AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void foreignCaseIdOutputIsRejected() {
        String caseId = createCase();
        stub.enqueueJson("""
                {"caseId":"FC-FOREIGN01","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"CLOSE",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"foreign case claim"}
                """);

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);

        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(auditTypes(caseId)).contains(
                AuditEventType.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void humanReviewBypassAttemptIsRejected() {
        String caseId = createCase();
        stub.enqueueJson("""
                {"caseId":"%s","classification":"GENUINE_TRANSACTION",
                 "riskLevel":"LOW","recommendedRoute":"CLOSE",
                 "confidence":0.9,"humanReviewRequired":false,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":[],
                 "rationale":"attempted review bypass"}
                """);

        InvestigationService.InvestigationResult result =
                investigationService.investigate(caseId);

        assertThat(result.mode()).isEqualTo(RecommendationMode.RULES_FALLBACK);
        assertThat(result.recommendation().humanReviewRequired()).isTrue();
    }
}
