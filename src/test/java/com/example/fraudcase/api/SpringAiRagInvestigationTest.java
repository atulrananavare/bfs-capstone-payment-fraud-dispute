package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.RecommendationMode;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.example.fraudcase.persistence.PolicyCitationRepository;
import com.example.fraudcase.persistence.RecommendationEntity;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 end-to-end: real ingestion + retrieval + grounding through the
 * full investigation flow with {@link StubChatModel} as the provider. No
 * credentials, no network.
 *
 * <p>Covers grounded SPRING_AI persistence with citations, fabricated
 * citation rejection, no-match abstention, the R005 fallback rule and the
 * case-scoped citations API.
 */
@SpringBootTest(properties = "capstone.processor-mode=spring-ai")
@AutoConfigureMockMvc
class SpringAiRagInvestigationTest {

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

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    private List<AuditEventType> auditTypes(String caseId) {
        return auditAppender.findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
    }

    private String createCase(IssueType issue, MerchantRecognition recognised,
                              TransactionChannel channel, String txnRef,
                              String statement) {
        var base = TestFixtures.validRequest();
        return fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(),
                new CustomerDetails(base.customer().maskedCustomerRef(),
                        base.customer().homeRegion()),
                new TransactionDetails(txnRef, base.transaction().amount(),
                        base.transaction().currency(), base.transaction().occurredAt(),
                        base.transaction().status(), channel,
                        base.transaction().maskedMerchant()),
                new ClaimDetails(issue, statement, base.claim().cardPossession(),
                        recognised),
                base.consent())).caseId();
    }

    /** Extract a valid passage chunkId (containing marker) from the prompt. */
    private static String citedChunkId(String prompt, String documentId) {
        Matcher m = Pattern.compile("<valid_policy_passage_ids>(.*?)</valid_policy_passage_ids>",
                Pattern.DOTALL).matcher(prompt);
        if (!m.find()) {
            throw new IllegalStateException("no passage id block in prompt");
        }
        return m.group(1).lines().map(String::trim)
                .filter(l -> l.startsWith("- ") && l.contains(documentId))
                .map(l -> l.substring(2))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no " + documentId + " passage supplied"));
    }

    /** Script the model to cite the real retrieved passage and return a
     *  valid grounded recommendation. */
    private void enqueueGrounded(String documentId, String classification,
                                 String route, String rationale) {
        stub.enqueueFunction(prompt -> {
            String promptText = prompt.getContents();
            String chunk = citedChunkId(promptText, documentId);
            String json = """
                    {"caseId":null,"classification":"%s",
                     "riskLevel":"HIGH","recommendedRoute":"%s",
                     "confidence":0.85,"humanReviewRequired":true,
                     "evidenceIds":[],"missingInformation":[],
                     "toolTraceIds":[],"policyCitations":["%s"],
                     "rationale":"%s"}
                    """.formatted(classification, route, chunk, rationale);
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content(json).build())));
        });
    }

    @Test
    void standardCnpGroundedRecommendationPersistsWithCitation() throws Exception {
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        enqueueGrounded("POL-CNP-017", "UNAUTHORISED_TRANSACTION",
                "FRAUD_INVESTIGATION",
                "Device and location anomalies corroborate the denial; "
                        + "policy POL-CNP-017 v3.2 routes to fraud investigation.");

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SPRING_AI"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        assertThat(rec.getMode()).isEqualTo(RecommendationMode.SPRING_AI);
        var citations = citationRepository
                .findByRecommendationId(rec.getRecommendationId());
        assertThat(citations).hasSize(1);
        var citation = citations.get(0);
        assertThat(citation.getDocumentId()).isEqualTo("POL-CNP-017");
        assertThat(citation.getVersion()).isEqualTo("3.2");
        assertThat(citation.getSection()).isEqualTo("3.2 Initial fraud routing");
        assertThat(citation.getChunkId()).isEqualTo("POL-CNP-017-3.2-S04-C01");
        assertThat(citation.getTitle())
                .isEqualTo("Card-Not-Present Dispute Handling Procedure");
        assertThat(citation.getPassage())
                .contains("fraud investigation");

        // Case-scoped citation API exposes the exact retrieved passage.
        mockMvc.perform(get("/api/cases/{id}/citations", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value("POL-CNP-017"))
                .andExpect(jsonPath("$[0].version").value("3.2"))
                .andExpect(jsonPath("$[0].passage").value(
                        org.hamcrest.Matchers.containsString(
                                "fraud investigation")));

        assertThat(auditTypes(caseId)).containsSubsequence(
                AuditEventType.POLICY_RETRIEVAL_STARTED,
                AuditEventType.POLICY_RETRIEVAL_COMPLETED);
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.POLICY_CITATION_ATTACHED);
    }

    @Test
    void merchantDisputeGroundedOnMerchantPolicy() throws Exception {
        String caseId = createCase(IssueType.CANCELLED_SERVICE,
                MerchantRecognition.RECOGNISED, TransactionChannel.CARD_NOT_PRESENT,
                "TXN-2026-784102", "I cancelled but was still charged.");
        enqueueGrounded("POL-MRD-004", "MERCHANT_DISPUTE",
                "DISPUTE_OPERATIONS",
                "Recognised merchant cancellation; policy POL-MRD-004 routes "
                        + "to dispute operations.");

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SPRING_AI"))
                .andExpect(jsonPath("$.classification").value("MERCHANT_DISPUTE"));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        var citations = citationRepository
                .findByRecommendationId(rec.getRecommendationId());
        assertThat(citations).isNotEmpty();
        assertThat(citations.get(0).getDocumentId()).isEqualTo("POL-MRD-004");
        assertThat(citations.get(0).getVersion()).isEqualTo("1.4");
    }

    @Test
    void duplicateProcessingGroundedOnDuplicatePolicy() throws Exception {
        String caseId = createCase(IssueType.DUPLICATE_CHARGE,
                MerchantRecognition.RECOGNISED, TransactionChannel.CARD_NOT_PRESENT,
                "TXN-5555-000002", "I was charged twice for one purchase.");
        enqueueGrounded("POL-DUP-009", "DUPLICATE_PROCESSING",
                "PAYMENT_OPERATIONS",
                "Duplicate fingerprint; policy POL-DUP-009 routes to "
                        + "payment operations.");

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("SPRING_AI"))
                .andExpect(jsonPath("$.classification").value("DUPLICATE_PROCESSING"));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        var citations = citationRepository
                .findByRecommendationId(rec.getRecommendationId());
        assertThat(citations).isNotEmpty();
        assertThat(citations.get(0).getDocumentId()).isEqualTo("POL-DUP-009");
    }

    @Test
    void fabricatedCitationRejectedAndFallsBackDeterministically() throws Exception {
        String caseId = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        stub.enqueueJson("""
                {"caseId":null,"classification":"UNAUTHORISED_TRANSACTION",
                 "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                 "confidence":0.9,"humanReviewRequired":true,
                 "evidenceIds":[],"missingInformation":[],
                 "toolTraceIds":[],"policyCitations":["POL-FAKE-999-v1-S01-C01"],
                 "rationale":"fabricated policy citation"}
                """);

        mockMvc.perform(post("/api/cases/{id}/investigations", caseId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.fallbackUsed").value(true));

        RecommendationEntity rec = investigationService.latestRecommendation(caseId);
        assertThat(citationRepository
                .findByRecommendationId(rec.getRecommendationId())).isEmpty();
        assertThat(auditTypes(caseId))
                .contains(AuditEventType.POLICY_CITATION_REJECTED);
    }

    @Test
    void citationsEndpointIsCaseScopedNoForeignAccess() throws Exception {
        String caseA = fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
        String caseB = createCase(IssueType.OTHER, MerchantRecognition.UNCERTAIN,
                TransactionChannel.BANK_TRANSFER, "TXN-2026-784102",
                "uncategorised question");
        enqueueGrounded("POL-CNP-017", "UNAUTHORISED_TRANSACTION",
                "FRAUD_INVESTIGATION", "grounded");
        investigationService.investigate(caseA);
        investigationService.investigate(caseB);

        // Case B has no citations; nothing from case A may leak into it.
        mockMvc.perform(get("/api/cases/{id}/citations", caseB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        // A path/doc query parameter cannot reach other documents.
        mockMvc.perform(get("/api/cases/{id}/citations?path=/etc/passwd", caseA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value("POL-CNP-017"));
    }
}
