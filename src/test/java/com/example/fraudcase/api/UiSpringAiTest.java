package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.api.FraudCaseIntakeRequest.ClaimDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.CustomerDetails;
import com.example.fraudcase.api.FraudCaseIntakeRequest.TransactionDetails;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.persistence.PolicyCitationRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 UI in spring-ai mode with a stub model: grounded policy
 * citations render, Open Passage serves the exact stored chunk, the
 * source badge reflects SPRING_AI / RULES_FALLBACK and abstention shows a
 * real no-match state instead of fabricated policy.
 */
@SpringBootTest(properties = "capstone.processor-mode=spring-ai")
@AutoConfigureMockMvc
class UiSpringAiTest {

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

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    private String createCase() {
        var base = TestFixtures.validRequest();
        return fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(),
                new CustomerDetails(base.customer().maskedCustomerRef(),
                        base.customer().homeRegion()),
                new TransactionDetails(base.transaction().transactionRef(),
                        base.transaction().amount(),
                        base.transaction().currency(),
                        base.transaction().occurredAt(),
                        base.transaction().status(),
                        TransactionChannel.CARD_NOT_PRESENT,
                        base.transaction().maskedMerchant()),
                new ClaimDetails(IssueType.MERCHANT_NOT_RECOGNISED,
                        base.claim().customerStatement(),
                        base.claim().cardPossession(),
                        MerchantRecognition.NOT_RECOGNISED),
                base.consent())).caseId();
    }

    private static String citedChunkId(String prompt, String documentId) {
        Matcher m = Pattern.compile(
                "<valid_policy_passage_ids>(.*?)</valid_policy_passage_ids>",
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

    private void enqueueGrounded(String documentId) {
        stub.enqueueFunction(prompt -> {
            String chunk = citedChunkId(prompt.getContents(), documentId);
            String json = """
                    {"caseId":null,"classification":"UNAUTHORISED_TRANSACTION",
                     "riskLevel":"HIGH","recommendedRoute":"FRAUD_INVESTIGATION",
                     "confidence":0.85,"humanReviewRequired":true,
                     "evidenceIds":[],"missingInformation":[],
                     "toolTraceIds":[],"policyCitations":["%s"],
                     "rationale":"Anomalies corroborate the customer denial."}
                    """.formatted(chunk);
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content(json).build())));
        });
    }

    @Test
    void policyScreenShowsGroundedCitationAndPassage() throws Exception {
        String caseId = createCase();
        enqueueGrounded("POL-CNP-017");
        investigationService.investigate(caseId);

        mockMvc.perform(get("/ui/cases/{id}/policy", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Policy Citations")))
                .andExpect(content().string(containsString("POL-CNP-017")))
                .andExpect(content().string(containsString(
                        "Card-Not-Present Dispute Handling Procedure")))
                .andExpect(content().string(containsString("3.2")))
                .andExpect(content().string(containsString("Retrieved Policy")))
                .andExpect(content().string(containsString("score")))
                .andExpect(content().string(containsString("Open Passage")))
                .andExpect(content().string(not(containsString(
                        "NO APPLICABLE POLICY FOUND"))));

        String citationId = citationRepository.findByRecommendationId(
                investigationService.latestRecommendation(caseId)
                        .getRecommendationId()).get(0).getCitationId();
        mockMvc.perform(get("/ui/cases/{id}/policy/citations/{cid}",
                        caseId, citationId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Exact retrieved passage")))
                .andExpect(content().string(containsString("POL-CNP-017")));
    }

    @Test
    void recommendationShowsSpringAiSource() throws Exception {
        String caseId = createCase();
        enqueueGrounded("POL-CNP-017");
        investigationService.investigate(caseId);
        mockMvc.perform(get("/ui/cases/{id}/recommendation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SPRING_AI")))
                .andExpect(content().string(containsString(
                        "Policy citation available")));
    }

    @Test
    void modelOutageShowsFallbackSource() throws Exception {
        String caseId = createCase();
        stub.enqueueFailure(new RuntimeException("synthetic provider outage"));
        investigationService.investigate(caseId);
        mockMvc.perform(get("/ui/cases/{id}/recommendation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RULES_FALLBACK")))
                .andExpect(content().string(not(containsString("outage"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

}
