package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request validation and availability rules for the message endpoints
 * (rules-mode context — conversational answering is unavailable here by
 * design, and nothing may be persisted on this path).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseMessageValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    private String createCase() {
        return fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
    }

    private static String body(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("message"));
    }

    @Test
    void missingMessageIsRejected() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedMessageIsRejected() throws Exception {
        String caseId = createCase();
        String huge = "x".repeat(2001);
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(huge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void cardLikeDigitRunIsRejected() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("the card was 4111 1111 1111 1111 ok")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void credentialLikeInputIsRejected() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("here is sk-AbCdEfGh12345678 thanks")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("use bearer abcdefghijklmnop")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("-----BEGIN RSA PRIVATE KEY-----")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markupMessageIsRejected() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("<script>alert(1)</script>")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void smuggledServerScopeFieldsAreRejected() throws Exception {
        String caseId = createCase();
        // A client cannot inject caseId/memory identifiers through the body.
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"FC-OTHER\",\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"conversationId\":\"FC-B\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCaseReturns404() throws Exception {
        mockMvc.perform(post("/api/cases/FC-NOPE99/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hello")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CASE_NOT_FOUND"));
        mockMvc.perform(get("/api/cases/FC-NOPE99/messages"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rulesModeRejectsConversationWithoutPersisting() throws Exception {
        String caseId = createCase();
        mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("What evidence supports the recommendation?")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONVERSATION_UNAVAILABLE"));
        // Nothing was persisted on the rejected path.
        mockMvc.perform(get("/api/cases/{id}/messages", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getHistoryIsEmptyInitially() throws Exception {
        String caseId = createCase();
        mockMvc.perform(get("/api/cases/{id}/messages", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
