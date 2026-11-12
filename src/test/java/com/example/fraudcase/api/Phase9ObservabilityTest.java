package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9 observability regression: actuator endpoints, metric counters,
 * tag cardinality and the correlation filter on the real context.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
@AutoConfigureMockMvc
class Phase9ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private double counterValue(String name) throws Exception {
        return counterValue(name, null);
    }

    private double counterValue(String name, String tag) throws Exception {
        MvcResult r = mockMvc.perform(get("/actuator/metrics/" + name
                        + (tag == null ? "" : "?tag=" + tag)))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        return node.get("measurements").get(0).get("value").asDouble();
    }

    private JsonNode createAndInvestigate() throws Exception {
        MvcResult intake = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()))
                .andExpect(status().isCreated()).andReturn();
        String caseId = objectMapper.readTree(intake.getResponse().getContentAsString())
                .get("caseId").asText();
        MvcResult inv = mockMvc.perform(post("/api/cases/{id}/investigations", caseId))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(inv.getResponse().getContentAsString());
        return objectMapper.createObjectNode()
                .put("caseId", caseId).set("inv", body);
    }

    // ---- actuator surface -------------------------------------------------

    @Test
    void healthEndpointIsUpWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoEndpointExposesStaticMetadataOnly() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("fraud-case-agent"));
    }

    @Test
    void metricsEndpointListsRegisteredMeters() throws Exception {
        // Counters/timers register lazily on first increment — run a full
        // flow first so every workflow meter exists.
        JsonNode ids = createAndInvestigate();
        mockMvc.perform(post("/api/cases/{id}/reviews", ids.get("caseId").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"ESCALATE","reason":"corroborated",
                                 "recommendationId":"%s",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgePolicyReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "acknowledgeContradictions":true}
                                """.formatted(ids.get("inv").get("recommendationId").asText())))
                .andExpect(status().isCreated());

        MvcResult r = mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode names = objectMapper
                .readTree(r.getResponse().getContentAsString()).get("names");
        assertThat(names.toString()).contains("fraudcase.cases.created",
                "fraudcase.investigations.started",
                "fraudcase.investigations.completed",
                "fraudcase.tools.invocations",
                "fraudcase.reviews.submitted");
    }

    @Test
    void sensitiveActuatorEndpointsStayUnexposed() throws Exception {
        for (String ep : new String[]{"env", "configprops", "beans",
                "heapdump", "threaddump", "loggers", "mappings"}) {
            mockMvc.perform(get("/actuator/" + ep))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- metric behavior ---------------------------------------------------

    @Test
    void workflowCountersIncrementOnRealFlow() throws Exception {
        double casesBefore = safeValue("fraudcase.cases.created");
        double startedBefore = safeValue("fraudcase.investigations.started");
        double completedBefore = safeValue("fraudcase.investigations.completed");
        double toolsBefore = safeValue("fraudcase.tools.invocations");
        double reviewsBefore = safeValue("fraudcase.reviews.submitted");

        JsonNode ids = createAndInvestigate();
        String recId = ids.get("inv").get("recommendationId").asText();
        String caseId = ids.get("caseId").asText();

        mockMvc.perform(post("/api/cases/{id}/reviews", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disposition":"ESCALATE","reason":"corroborated",
                                 "recommendationId":"%s",
                                 "acknowledgeEvidenceReviewed":true,
                                 "acknowledgePolicyReviewed":true,
                                 "acknowledgeMissingInformation":true,
                                 "acknowledgeContradictions":true}
                                """.formatted(recId)))
                .andExpect(status().isCreated());

        assertThat(counterValue("fraudcase.cases.created"))
                .isGreaterThan(casesBefore);
        assertThat(counterValue("fraudcase.investigations.started"))
                .isGreaterThan(startedBefore);
        assertThat(counterValue("fraudcase.investigations.completed"))
                .isGreaterThan(completedBefore);
        assertThat(counterValue("fraudcase.tools.invocations"))
                .isGreaterThanOrEqualTo(toolsBefore + 6);
        assertThat(counterValue("fraudcase.reviews.submitted"))
                .isGreaterThan(reviewsBefore);
        assertThat(counterValue("fraudcase.investigation.duration",
                "mode:RULES")).isGreaterThan(0);
    }

    /** Reads a counter that may not be registered yet; treats missing
     *  meters as zero. MockMvc assertions throw AssertionError on a 404,
     *  so Throwable is caught rather than Exception. */
    private double safeValue(String name) {
        try {
            return counterValue(name);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Test
    void failedOperationsIncrementFailureMetrics() throws Exception {
        double rejectedBefore = safeValue("fraudcase.reviews.rejected");
        double invFailedBefore = safeValue("fraudcase.investigations.failed");

        // invalid review on unknown case -> not_found rejection
        mockMvc.perform(post("/api/cases/{id}/reviews", "FC-OBS00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"ESCALATE\",\"reason\":\"x\","
                                + "\"recommendationId\":\"R\"}"))
                .andExpect(status().isNotFound());
        // second investigation on a READY case -> not_permitted failure.
        // (Unknown-case investigate 404s in the controller pre-check and
        // never reaches the service — it is a lookup miss, not an
        // investigation failure.)
        JsonNode ids = createAndInvestigate();
        mockMvc.perform(post("/api/cases/{id}/investigations",
                        ids.get("caseId").asText()))
                .andExpect(status().isConflict());

        assertThat(counterValue("fraudcase.reviews.rejected"))
                .isGreaterThan(rejectedBefore);
        assertThat(counterValue("fraudcase.investigations.failed"))
                .isGreaterThan(invFailedBefore);
    }

    @Test
    void metricTagsStayLowCardinalityAndSafe() throws Exception {
        createAndInvestigate();
        MvcResult r = mockMvc.perform(get("/actuator/metrics/fraudcase.investigations.completed"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        // No case/customer/transaction identifiers as tag values.
        Pattern forbidden = Pattern.compile("FC-[0-9A-F]{8}|CUST-|TXN-");
        assertThat(forbidden.matcher(body).find()).isFalse();
        JsonNode tags = objectMapper.readTree(body).get("availableTags");
        for (JsonNode t : tags) {
            assertThat(t.get("tag").asText()).isIn(
                    "mode", "tool", "disposition", "reason", "source", "operation");
        }
    }

    // ---- correlation -------------------------------------------------------

    @Test
    void correlationIdEchoedOnResponses() throws Exception {
        mockMvc.perform(get("/api/cases/{id}", "FC-OBS00001")
                        .header(CorrelationIdFilter.HEADER, "obs-req-123"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "obs-req-123"));
        // generated when absent
        mockMvc.perform(get("/api/cases/{id}", "FC-OBS00001"))
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }
}
