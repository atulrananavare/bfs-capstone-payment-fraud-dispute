package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.persistence.ReviewDispositionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 T01–T12 end-to-end matrix on the real application context
 * (H2 + Flyway + JPA + rules agent). Mirrors the documented matrix in
 * {@code docs/phase-8-test-matrix.md}; deterministic — no live provider.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
@AutoConfigureMockMvc
class Phase8EndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private ReviewDispositionRepository reviewRepository;

    /** T01+T02: intake -> investigate; returns [caseId, recommendationId]. */
    private String[] investigatedCase() throws Exception {
        MvcResult intake = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").exists())
                .andExpect(jsonPath("$.state").value("NEW"))
                .andReturn();
        String caseId = objectMapper.readTree(intake.getResponse().getContentAsString())
                .get("caseId").asText();

        MvcResult investigation = mockMvc.perform(post("/api/cases/{id}/investigations", caseId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.toolsExecuted").value(6))
                .andExpect(jsonPath("$.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.ruleIds").isArray())
                .andReturn();
        String recId = objectMapper.readTree(investigation.getResponse().getContentAsString())
                .get("recommendationId").asText();
        return new String[]{caseId, recId};
    }

    private static String reviewJson(String disposition, String reason,
                                     String recId, String extraFields) {
        return """
                {"disposition":"%s","reason":"%s",
                 "acknowledgeEvidenceReviewed":true,
                 "acknowledgePolicyReviewed":true,
                 "acknowledgeMissingInformation":true,
                 "acknowledgeContradictions":true,
                 "recommendationId":"%s"%s}
                """.formatted(disposition, reason, recId,
                extraFields == null ? "" : "," + extraFields);
    }

    private String caseState(String caseId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("state").asText();
    }

    // ---- T07: approve-equivalent disposition routes the case -------------

    @Test
    void t07_escalateRoutesCaseAndPersistsDisposition() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("ESCALATE", "Evidence corroborates the claim; escalating.",
                                ids[1], "\"clientReference\":\"p8-t07\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingCaseStatus").value("ROUTED"))
                .andExpect(jsonPath("$.reviewerId").value("TRAINING-REVIEWER-001"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
        assertThat(caseState(ids[0])).isEqualTo("ROUTED");
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).hasSize(1);
    }

    // ---- T08: REQUEST_MORE_INFORMATION returns to INCOMPLETE -------------

    @Test
    void t08_requestMoreInformationReturnsIncomplete() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQUEST_MORE_INFORMATION",
                                "Authentication evidence required before disposition.", ids[1], null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingCaseStatus").value("INCOMPLETE"));
        assertThat(caseState(ids[0])).isEqualTo("INCOMPLETE");
    }

    @Test
    void t08_rerouteWithoutTargetRouteIsRejected() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REROUTE", "Missing target route must fail.", ids[1], null)))
                .andExpect(status().isBadRequest());
        assertThat(caseState(ids[0])).isEqualTo("READY_FOR_REVIEW");
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).isEmpty();
    }

    // ---- T09: CLOSE reaches the terminal state ----------------------------

    @Test
    void t09_closeIsTerminalAndFurtherReviewsAreRejected() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("CLOSE", "Customer confirmed the transaction.", ids[1], null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingCaseStatus").value("CLOSED"));

        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("ESCALATE", "Second disposition must fail.", ids[1], null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REVIEW_NOT_PERMITTED"));
        assertThat(caseState(ids[0])).isEqualTo("CLOSED");
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).hasSize(1);
    }

    // ---- T10: stale/foreign recommendation + idempotent replay -------------

    @Test
    void t10_foreignRecommendationIdIsRejected() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("ESCALATE", "Foreign rec id must fail.",
                                "REC-0000000000FOREIGN", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_RECOMMENDATION"));
        assertThat(caseState(ids[0])).isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void t10_duplicateClientReferenceReplaysStoredDisposition() throws Exception {
        var ids = investigatedCase();
        String body = reviewJson("ESCALATE", "First submission.", ids[1],
                "\"clientReference\":\"p8-t10-dup\"");
        MvcResult first = mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andReturn();

        JsonNode f = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode r = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(r.get("reviewId").asText()).isEqualTo(f.get("reviewId").asText());
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).hasSize(1);
    }

    // ---- T11: spoofed identity/state fields are rejected -------------------

    @Test
    void t11_spoofedReviewerFieldsAreRejected() throws Exception {
        var ids = investigatedCase();
        for (String field : new String[]{
                "\"reviewerId\":\"ADMIN-ROOT-999\"",
                "\"nextState\":\"CLOSED\"",
                "\"reviewedAt\":\"2020-01-01T00:00:00Z\""}) {
            mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reviewJson("ESCALATE", "Spoof attempt.", ids[1], field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }
        assertThat(caseState(ids[0])).isEqualTo("READY_FOR_REVIEW");
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).isEmpty();
    }

    // ---- T12 (canonical): mandatory reviewer reason ------------------------

    @Test
    void t12_blankOrMissingReasonIsRejectedWithNothingPersisted() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("OVERRIDE", "   ", ids[1],
                                "\"targetRoute\":\"DISPUTE_OPERATIONS\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cases/{id}/reviews", ids[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"OVERRIDE","recommendationId":"%s",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgePolicyReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "acknowledgeContradictions":true}
                                """.formatted(ids[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        assertThat(caseState(ids[0])).isEqualTo("READY_FOR_REVIEW");
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(ids[0])).isEmpty();
    }

    // ---- S3/S4: failure & security regression guards ------------------------

    @Test
    void s3_unknownCaseReturnsSafeNotFound() throws Exception {
        mockMvc.perform(get("/api/cases/{id}", "FC-NOPE404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CASE_NOT_FOUND"));
        mockMvc.perform(post("/api/cases/{id}/investigations", "FC-NOPE404"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cases/{id}/reviews", "FC-NOPE404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"ESCALATE\",\"reason\":\"x\",\"recommendationId\":\"R\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void s3_malformedAndEnumInputAreRejectedSafely() throws Exception {
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()
                                .replace("\"WEB_PORTAL\"", "\"MIND_CONTROL\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void s4_hiddenPayloadFieldsAreRejectedOnIntake() throws Exception {
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()
                                .replaceFirst("\\{",
                                        "{\"caseId\":\"FC-HACKED01\",\"state\":\"CLOSED\",\"adminOverride\":true,")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void s4_unmatchedPathReturnsNotFoundNotInternalError() throws Exception {
        // Regression: non-matching paths used to fall through to the
        // static-resource handler and surface as INTERNAL_ERROR (verified
        // live with an encoded <script> path segment).
        mockMvc.perform(get("/api/cases/EXTRA/SEGMENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void s2_reviewBeforeInvestigationFindsNoRecommendation() throws Exception {
        MvcResult intake = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()))
                .andExpect(status().isCreated()).andReturn();
        String caseId = objectMapper.readTree(intake.getResponse().getContentAsString())
                .get("caseId").asText();
        mockMvc.perform(post("/api/cases/{id}/reviews", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("ESCALATE", "Bypass attempt.", "REC-NONE", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECOMMENDATION_NOT_FOUND"));
        assertThat(reviewRepository.findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
    }

    @Test
    void s3_doubleInvestigationIsRejected() throws Exception {
        var ids = investigatedCase();
        mockMvc.perform(post("/api/cases/{id}/investigations", ids[0]))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVESTIGATION_NOT_PERMITTED"));
    }
}
