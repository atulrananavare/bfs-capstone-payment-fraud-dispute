package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.domain.FraudCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseController.class)
class FraudCaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudCaseService fraudCaseService;

    @Test
    void validIntakeReturnsCreatedWithServerGeneratedCaseId() throws Exception {
        FraudCaseIntakeRequest request = TestFixtures.validRequest();
        FraudCase created = new FraudCase(
                "FC-AB12CD34",
                request.reportedAt(),
                request.channel(),
                "unauthenticated-analyst",
                new com.example.fraudcase.domain.CustomerFacts(
                        request.customer().maskedCustomerRef(), request.customer().homeRegion()),
                new com.example.fraudcase.domain.TransactionFacts(
                        request.transaction().transactionRef(), request.transaction().amount(),
                        request.transaction().currency(), request.transaction().occurredAt(),
                        request.transaction().status(), request.transaction().channel(),
                        request.transaction().maskedMerchant()),
                new com.example.fraudcase.domain.ClaimFacts(
                        request.claim().issueType(), request.claim().customerStatement(),
                        request.claim().cardPossession(), request.claim().merchantRecognised()),
                OffsetDateTime.now());
        when(fraudCaseService.createCase(any())).thenReturn(created);

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/cases/FC-AB12CD34")))
                .andExpect(jsonPath("$.caseId").value("FC-AB12CD34"))
                .andExpect(jsonPath("$.state").value("NEW"));
    }

    @Test
    void missingMandatoryFieldReturnsStructuredFieldErrors() throws Exception {
        String json = TestFixtures.validRequestJson().replaceFirst(
                "\"channel\": \"WEB_PORTAL\",", "");

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='channel')]").exists())
                .andExpect(jsonPath("$.fieldErrors[0].field").isString())
                .andExpect(jsonPath("$.fieldErrors[0].message").isString());
    }

    @Test
    void unknownEnumValueIsRejectedSafely() throws Exception {
        String json = TestFixtures.validRequestJson().replace(
                "MERCHANT_NOT_RECOGNISED", "BOGUS_ENUM");

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                // no stack trace or parser internals are exposed
                .andExpect(jsonPath("$.message").value(
                        "Request body could not be parsed: malformed JSON or unsupported value"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void unknownJsonPropertyIsRejected() throws Exception {
        String json = TestFixtures.validRequestJson().replaceFirst(
                "\"reportedAt\"",
                "\"caseId\": \"CLIENT-SUPPLIED\", \"reportedAt\"");

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void scriptMarkupInStatementReturnsFieldError() throws Exception {
        String json = TestFixtures.validRequestJson().replace(
                "I do not recognise this purchase and still have my card.",
                "<script>alert(1)</script>");

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='claim.customerStatement')]").exists());
    }

    @Test
    void unknownCaseReturnsSafeNotFound() throws Exception {
        when(fraudCaseService.getCase("FC-NOPE1234"))
                .thenThrow(new com.example.fraudcase.application.CaseNotFoundException("FC-NOPE1234"));

        mockMvc.perform(get("/api/cases/FC-NOPE1234"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CASE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString());
    }
}
