package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * Phase 10 gap fix: analyst worklist — GET /api/cases summary list and the
 * /ui/cases index page. Read-only, masked summaries only.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
@AutoConfigureMockMvc
class Phase10CaseListTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String createCase() throws Exception {
        MvcResult intake = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestFixtures.validRequestJson()))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(intake.getResponse().getContentAsString())
                .get("caseId").asText();
    }

    @Test
    void apiListReturnsMaskedSummariesNewestFirst() throws Exception {
        String caseId = createCase();

        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value(caseId))
                .andExpect(jsonPath("$[*].caseId", hasItem(caseId)))
                .andExpect(jsonPath("$[0].state").value("NEW"))
                .andExpect(jsonPath("$[0].issueType")
                        .value("MERCHANT_NOT_RECOGNISED"))
                .andExpect(jsonPath("$[0].maskedMerchant").exists())
                // summary exposes no raw statement or unmasked identifiers
                .andExpect(jsonPath("$[0].customerStatement").doesNotExist())
                .andExpect(jsonPath("$[0].transactionRef").doesNotExist());
    }

    @Test
    void uiCaseListRendersLinksToWorkbenches() throws Exception {
        String caseId = createCase();

        mockMvc.perform(get("/ui/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(caseId)))
                .andExpect(content().string(
                        containsString("/ui/cases/" + caseId)))
                .andExpect(content().string(containsString("New Case Intake")));
    }

    @Test
    void homePageLinksToCaseList() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("/ui/cases")));
    }
}
