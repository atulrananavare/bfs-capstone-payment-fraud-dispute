package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end deterministic flow on the real application context (H2 +
 * Flyway + JPA + rules agent). No AI/model/provider is involved anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvestigationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditEventAppender auditAppender;

    private String createCase(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("caseId").asText();
    }

    private String caseJson(String customerRef, String txnRef, String merchant,
                            String issueType, String merchantRecognised) {
        return """
                {
                  "reportedAt": "2026-11-11T09:15:00Z",
                  "channel": "WEB_PORTAL",
                  "customer": {"maskedCustomerRef": "%s", "homeRegion": "IN-KA"},
                  "transaction": {
                    "transactionRef": "%s", "amount": 48750.00, "currency": "INR",
                    "occurredAt": "2026-11-06T08:42:00+05:30", "status": "POSTED",
                    "channel": "CARD_NOT_PRESENT", "maskedMerchant": "%s"
                  },
                  "claim": {
                    "issueType": "%s",
                    "customerStatement": "I do not recognise this purchase and still have my card.",
                    "cardPossession": "IN_POSSESSION",
                    "merchantRecognised": "%s"
                  },
                  "consent": {"syntheticDataConfirmed": true, "reviewRequired": true}
                }
                """.formatted(customerRef, txnRef, merchant, issueType, merchantRecognised);
    }

    @Test
    void sampleCaseFlowsThroughDeterministicCore() throws Exception {
        String caseId = createCase(TestFixtures.validRequestJson());
        assertThat(caseId).startsWith("FC-");

        // GET case summary
        mockMvc.perform(get("/api/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.state").value("NEW"))
                .andExpect(jsonPath("$.maskedCustomerRef").value("CUST-••••-4821"));

        // POST investigation → deterministic rules analysis
        MvcResult inv = mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.classification").value("UNAUTHORISED_TRANSACTION"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.recommendedRoute").value("FRAUD_INVESTIGATION"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.ruleIds[0]").value("R003"))
                .andExpect(jsonPath("$.toolsExecuted").value(6))
                .andReturn();

        JsonNode summary = objectMapper.readTree(inv.getResponse().getContentAsString());
        assertThat(summary.get("missingInformation").toString())
                .contains("authentication outcome");

        // GET evidence — customer claim + tool-observed items, provenance set
        MvcResult evidence = mockMvc.perform(get("/api/cases/" + caseId + "/evidence"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(evidence.getResponse().getContentAsString());
        assertThat(items.size()).isGreaterThanOrEqualTo(6);
        assertThat(items.toString()).contains("CUSTOMER_INTAKE");
        assertThat(items.toString()).contains("DEVICE_TOOL");
        assertThat(items.toString()).contains("deviceKnown=false");
        assertThat(items.toString()).contains("anomalyLevel=HIGH");
        // no raw sensitive values anywhere in evidence output
        assertThat(items.toString()).doesNotContain("4111");

        // GET tool traces — 6 executions, hashed requests, redacted summaries
        MvcResult traces = mockMvc.perform(get("/api/cases/" + caseId + "/tool-traces"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode traceList = objectMapper.readTree(traces.getResponse().getContentAsString());
        assertThat(traceList.size()).isEqualTo(6);
        for (JsonNode t : traceList) {
            assertThat(t.get("requestHash").asText()).hasSize(64);
            assertThat(t.get("status").asText()).isIn("SUCCESS", "NOT_FOUND");
            assertThat(t.get("traceId").asText()).startsWith("TRC-");
        }

        // GET recommendation — validated, human review always required
        mockMvc.perform(get("/api/cases/" + caseId + "/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RULES"))
                .andExpect(jsonPath("$.classification").value("UNAUTHORISED_TRANSACTION"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.policyCitations").isEmpty())
                .andExpect(jsonPath("$.evidenceIds").isNotEmpty())
                .andExpect(jsonPath("$.rationale").isNotEmpty());
    }

    @Test
    void notFoundTransactionLeadsToIncomplete() throws Exception {
        String caseId = createCase(caseJson(
                "CUST-••••-4821", "TXN-4040-404040", "NORTHSTAR DIGITAL ••••73",
                "MERCHANT_NOT_RECOGNISED", "NOT_RECOGNISED"));

        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("INCOMPLETE"))
                .andExpect(jsonPath("$.classification").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.recommendedRoute").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.ruleIds[0]").value("R001"));
    }

    @Test
    void restartFromIncompleteIsIdempotentForEvidence() throws Exception {
        String caseId = createCase(caseJson(
                "CUST-••••-4821", "TXN-4040-404040", "NORTHSTAR DIGITAL ••••73",
                "MERCHANT_NOT_RECOGNISED", "NOT_RECOGNISED"));

        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated());
        // Restart: INCOMPLETE -> ENRICHING -> INCOMPLETE again.
        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("INCOMPLETE"));

        // New traces were appended per execution; evidence stays idempotent.
        MvcResult traces = mockMvc.perform(get("/api/cases/" + caseId + "/tool-traces"))
                .andExpect(status().isOk()).andReturn();
        JsonNode traceList = objectMapper.readTree(traces.getResponse().getContentAsString());
        assertThat(traceList.size()).isEqualTo(12);

        MvcResult evidence = mockMvc.perform(get("/api/cases/" + caseId + "/evidence"))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = objectMapper.readTree(evidence.getResponse().getContentAsString());
        int firstCount = first.size();

        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated());
        MvcResult evidence2 = mockMvc.perform(get("/api/cases/" + caseId + "/evidence"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(evidence2.getResponse().getContentAsString()).size())
                .isEqualTo(firstCount);
    }

    @Test
    void restartOnReadyForReviewIsRejected() throws Exception {
        String caseId = createCase(TestFixtures.validRequestJson());
        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVESTIGATION_NOT_PERMITTED"));
    }

    @Test
    void unknownCaseIdsAreSafe404s() throws Exception {
        mockMvc.perform(get("/api/cases/FC-NOPE9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CASE_NOT_FOUND"));
        mockMvc.perform(post("/api/cases/FC-NOPE9999/investigations"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cases/FC-NOPE9999/evidence"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cases/FC-NOPE9999/tool-traces"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cases/FC-NOPE9999/recommendation"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recommendationMissingBeforeInvestigation() throws Exception {
        String caseId = createCase(TestFixtures.validRequestJson());
        mockMvc.perform(get("/api/cases/" + caseId + "/recommendation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECOMMENDATION_NOT_FOUND"));
    }

    @Test
    void auditTrailCapturesFullLifecycleForSampleCase() throws Exception {
        String caseId = createCase(TestFixtures.validRequestJson());
        mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated());

        List<AuditEventEntity> events = auditAppender.findByCaseIdOrderByRecordedAt(caseId);
        var types = events.stream().map(AuditEventEntity::getEventType).toList();

        assertThat(types).contains(
                AuditEventType.CASE_CREATED,
                AuditEventType.INVESTIGATION_STARTED,
                AuditEventType.RECOMMENDATION_CREATED);
        assertThat(types.stream().filter(t -> t == AuditEventType.TOOL_EXECUTED).count())
                .isEqualTo(6);
        assertThat(types.stream().filter(t -> t == AuditEventType.CASE_STATE_CHANGED).count())
                .isGreaterThanOrEqualTo(2);

        for (AuditEventEntity e : events) {
            assertThat(e.getCaseId()).isEqualTo(caseId);
            assertThat(e.getRecordedAt()).isNotNull();
            assertThat(e.getPayloadHash()).hasSize(64);
            assertThat(e.getActorType()).isNotNull();
        }
    }

    @Test
    void toolEvidenceIsLinkedToItsInvestigation() throws Exception {
        String caseId = createCase(TestFixtures.validRequestJson());
        MvcResult inv = mockMvc.perform(post("/api/cases/" + caseId + "/investigations"))
                .andExpect(status().isCreated())
                .andReturn();
        String investigationId = objectMapper
                .readTree(inv.getResponse().getContentAsString())
                .get("investigationId").asText();

        MvcResult evidence = mockMvc.perform(get("/api/cases/" + caseId + "/evidence"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(evidence.getResponse().getContentAsString());
        for (JsonNode item : items) {
            assertThat(item.get("investigationId").asText()).isEqualTo(investigationId);
        }
    }

    @Test
    void errorResponsesContainNoStackTrace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bogus\": true}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("at com.");
        assertThat(body).doesNotContain("stackTrace");
    }
}
