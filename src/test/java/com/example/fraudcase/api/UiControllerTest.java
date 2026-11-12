package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.ToolStatus;
import com.example.fraudcase.persistence.ReviewDispositionRepository;
import com.example.fraudcase.tools.TransactionDetailsTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 UI verification — the six workbench screens in rules mode
 * (deterministic, no provider). Verifies routes, safe content, validation,
 * CSRF, review flow/T12, escaping and case isolation.
 */
@SpringBootTest(properties = "capstone.processor-mode=rules")
@AutoConfigureMockMvc
class UiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FraudCaseService fraudCaseService;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private ReviewDispositionRepository reviewRepository;

    @Autowired
    private TransactionDetailsTool transactionTool;

    // ---- helpers -----------------------------------------------------------

    private String createCase() {
        return fraudCaseService.createCase(TestFixtures.validRequest()).caseId();
    }

    private String investigatedCase() {
        String caseId = createCase();
        investigationService.investigate(caseId);
        return caseId;
    }

    private String recId(String caseId) {
        return investigationService.latestRecommendation(caseId)
                .getRecommendationId();
    }

    private static org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder intakePost(
                    String statement, String amount) {
        return post("/ui/cases")
                .param("maskedCustomerRef", "CUST-••••-4821")
                .param("homeRegion", "IN-KA")
                .param("transactionRef", "TXN-2026-784102")
                .param("amount", amount)
                .param("currency", "INR")
                .param("occurredAt", "2026-11-06T08:42")
                .param("status", "POSTED")
                .param("channel", "CARD_NOT_PRESENT")
                .param("maskedMerchant", "NORTHSTAR DIGITAL ••••73")
                .param("issueType", "MERCHANT_NOT_RECOGNISED")
                .param("customerStatement", statement)
                .param("cardPossession", "IN_POSSESSION")
                .param("merchantRecognised", "NOT_RECOGNISED")
                .param("reportChannel", "WEB_PORTAL")
                .param("syntheticDataConfirmed", "true")
                .param("reviewRequired", "true");
    }

    private static org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder intakePost() {
        return intakePost(
                "I do not recognise this purchase and still have my card.",
                "48750.00");
    }

    private org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder reviewPost(
                    String caseId, String disposition, String reason,
                    String recId, String targetRoute) {
        var req = post("/ui/cases/{id}/review", caseId)
                .with(csrf())
                .param("disposition", disposition)
                .param("reason", reason)
                .param("recommendationId", recId)
                .param("clientReference", "UI-TEST-" + caseId + "-" + disposition)
                .param("acknowledgeEvidenceReviewed", "true")
                .param("acknowledgePolicyReviewed", "true")
                .param("acknowledgeMissingInformation", "true")
                .param("acknowledgeContradictions", "true");
        if (targetRoute != null) {
            req.param("targetRoute", targetRoute);
        }
        return req;
    }

    // ---- screen 1: intake ---------------------------------------------------

    @Test
    void intakeScreenRenders() throws Exception {
        mockMvc.perform(get("/ui/cases/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Case Intake")))
                .andExpect(content().string(
                        containsString("SYNTHETIC TRAINING DATA")))
                .andExpect(content().string(
                        containsString("Masked customer reference")))
                .andExpect(content().string(
                        containsString("Use masked/tokenised references only")))
                .andExpect(content().string(
                        containsString("Start Investigation")))
                .andExpect(content().string(
                        containsString("Customer statement")))
                .andExpect(content().string(
                        containsString("draft state")));
    }

    @Test
    void submitValidIntakeStartsInvestigation() throws Exception {
        var result = mockMvc.perform(intakePost().with(csrf())
                        .param("intent", "investigate"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).matches("/ui/cases/FC-[0-9A-F]+/investigation");
        String caseId = location.split("/")[3];
        assertThat(investigationService.tracesFor(caseId)).isNotEmpty();
    }

    @Test
    void submitInvalidIntakeShowsFieldErrors() throws Exception {
        mockMvc.perform(intakePost("", "-5").with(csrf())
                        .param("intent", "create"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("field-error")))
                // Safe values are preserved after validation failure.
                .andExpect(content().string(
                        containsString("NORTHSTAR DIGITAL")));
    }

    @Test
    void intakeRejectsScriptMarkup() throws Exception {
        mockMvc.perform(intakePost("<script>alert('fraud')</script>",
                        "100.00").with(csrf())
                        .param("intent", "create"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("field-error")))
                .andExpect(content().string(not(containsString(
                        "<script>alert"))));
    }

    // ---- screen 2: investigation ---------------------------------------------

    @Test
    void investigationEmptyStateAndStart() throws Exception {
        String caseId = createCase();
        mockMvc.perform(get("/ui/cases/{id}/investigation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Investigation")))
                .andExpect(content().string(containsString(
                        "No investigation has run yet")))
                .andExpect(content().string(
                        containsString("Start Investigation")));

        mockMvc.perform(post("/ui/cases/{id}/investigate", caseId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(investigationService.tracesFor(caseId)).isNotEmpty();
    }

    @Test
    void investigationShowsRealToolTracesAndMissingInfo() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/investigation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("getTransactionDetails")))
                .andExpect(content().string(containsString("getDeviceSignals")))
                .andExpect(content().string(
                        containsString("Investigation plan")))
                .andExpect(content().string(containsString("Duration")))
                .andExpect(content().string(containsString("Trace ID")))
                .andExpect(content().string(containsString(
                        "Unresolved / missing information")))
                .andExpect(content().string(containsString(
                        "authentication outcome unavailable")))
                // No chain-of-thought markers.
                .andExpect(content().string(not(containsString(
                        "chainOfThought"))))
                .andExpect(content().string(not(containsString(
                        "chain-of-thought"))));
    }

    @Test
    void investigationShowsToolFailure() throws Exception {
        // Find a deterministic reference that yields a non-success outcome.
        String notFoundRef = null;
        for (int i = 0; i < 200 && notFoundRef == null; i++) {
            String ref = "TXN-TEST-" + String.format("%04d", i);
            if (transactionTool.execute(
                    new TransactionDetailsTool.Request(ref)).status()
                    != ToolStatus.SUCCESS) {
                notFoundRef = ref;
            }
        }
        assertThat(notFoundRef).isNotNull();
        var base = TestFixtures.validRequest();
        String caseId = fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                new FraudCaseIntakeRequest.TransactionDetails(
                        notFoundRef, base.transaction().amount(),
                        base.transaction().currency(),
                        base.transaction().occurredAt(),
                        base.transaction().status(),
                        base.transaction().channel(),
                        base.transaction().maskedMerchant()),
                base.claim(), base.consent())).caseId();
        investigationService.investigate(caseId);
        mockMvc.perform(get("/ui/cases/{id}/investigation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("NOT_FOUND")))
                .andExpect(content().string(containsString("Trace ID")));
    }

    // ---- screen 3: recommendation ----------------------------------------------

    @Test
    void recommendationScreenShowsContract() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/recommendation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("ADVISORY RECOMMENDATION")))
                .andExpect(content().string(
                        containsString("UNAUTHORISED_TRANSACTION")))
                .andExpect(content().string(containsString("HIGH")))
                .andExpect(content().string(
                        containsString("FRAUD_INVESTIGATION")))
                .andExpect(content().string(containsString("85%")))
                .andExpect(content().string(containsString("REQUIRED")))
                .andExpect(content().string(containsString("RULES")))
                .andExpect(content().string(containsString("Rationale")))
                .andExpect(content().string(containsString(
                        "Missing information")))
                .andExpect(content().string(containsString(
                        "Continue to Human Review")))
                // Follow-up is a support feature, unavailable in rules.
                .andExpect(content().string(containsString(
                        "unavailable in RULES mode")));
    }

    @Test
    void recommendationEmptyState() throws Exception {
        String caseId = createCase();
        mockMvc.perform(get("/ui/cases/{id}/recommendation", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "No validated recommendation is available")));
    }

    // ---- screen 4: evidence ---------------------------------------------------

    @Test
    void evidenceScreenSeparatesClaimFromTools() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/evidence", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Evidence Workspace")))
                .andExpect(content().string(
                        containsString("Customer Reported")))
                .andExpect(content().string(
                        containsString("Observed by Tool")))
                .andExpect(content().string(containsString(
                        "I do not recognise this purchase")))
                .andExpect(content().string(containsString("Evidence ID")))
                .andExpect(content().string(containsString("Provenance")))
                .andExpect(content().string(containsString("Observed at")))
                .andExpect(content().string(not(containsString(
                        "Verified evidence"))));
    }

    @Test
    void evidenceEmptyState() throws Exception {
        String caseId = createCase();
        mockMvc.perform(get("/ui/cases/{id}/evidence", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "No evidence has been collected for this case yet")));
    }

    // ---- screen 5: policy -------------------------------------------------------

    @Test
    void policyScreenRulesModeShowsNotRequestedAndConfig() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/policy", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Policy Citations")))
                .andExpect(content().string(containsString("Top-K")))
                .andExpect(content().string(containsString(
                        "Similarity threshold")))
                .andExpect(content().string(containsString("NOT_REQUESTED")))
                .andExpect(content().string(containsString(
                        "was not performed")));
    }

    @Test
    void unknownCitationPassageIs404() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/policy/citations/{cid}",
                        caseId, "CIT-DOESNOTEXIST"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Not found")));
    }

    // ---- screen 6: human review --------------------------------------------------

    @Test
    void reviewScreenShowsContextAndControls() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Human Review")))
                .andExpect(content().string(containsString(
                        "Human decision required")))
                .andExpect(content().string(containsString(
                        "Agent recommendation")))
                .andExpect(content().string(containsString("ESCALATE")))
                .andExpect(content().string(containsString(
                        "REQUEST_MORE_INFORMATION")))
                .andExpect(content().string(containsString("REROUTE")))
                .andExpect(content().string(containsString("OVERRIDE")))
                .andExpect(content().string(containsString("CLOSE")))
                .andExpect(content().string(containsString(
                        "TRAINING-REVIEWER-001")))
                .andExpect(content().string(containsString("read only")))
                .andExpect(content().string(containsString(
                        "Reviewer rationale")))
                .andExpect(content().string(containsString(
                        "Evidence reviewed")))
                .andExpect(content().string(containsString(
                        "Audit preview")))
                .andExpect(content().string(containsString(
                        "Submit Review")));
    }

    @Test
    void reviewNotReadyState() throws Exception {
        String caseId = createCase();
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "This case is not ready for human review")));
    }

    @Test
    void t12UiOverrideWithoutReasonIsRejected() throws Exception {
        String caseId = investigatedCase();
        String rec = recId(caseId);
        mockMvc.perform(reviewPost(caseId, "OVERRIDE", "   ", rec,
                        "MANUAL_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Review was not submitted")));
        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.READY_FOR_REVIEW);
        assertThat(investigationService.latestRecommendation(caseId)
                .isHumanReviewRequired()).isTrue();
    }

    @Test
    void validEscalateThroughUi() throws Exception {
        String caseId = investigatedCase();
        String rec = recId(caseId);
        mockMvc.perform(reviewPost(caseId, "ESCALATE",
                        "Customer denial and anomalies support escalation.", rec,
                        null))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        "/ui/cases/" + caseId + "/review"));

        // The review history on the same screen shows the recorded,
        // immutable disposition with reviewer provenance.
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Recorded dispositions")))
                .andExpect(content().string(containsString("ESCALATE")))
                .andExpect(content().string(containsString(
                        "TRAINING-REVIEWER-001")))
                .andExpect(content().string(containsString(
                        "Customer denial and anomalies")));
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.ROUTED);
        var reviews = reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).getReviewerId())
                .isEqualTo("TRAINING-REVIEWER-001");
    }

    @Test
    void staleRecommendationShowsConflictMessage() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(reviewPost(caseId, "ESCALATE", "reason", "REC-WRONGID",
                        null))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "The recommendation changed after this review was "
                                + "opened")));
        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
    }

    @Test
    void closedCaseReviewBlocked() throws Exception {
        String caseId = investigatedCase();
        String rec = recId(caseId);
        mockMvc.perform(reviewPost(caseId, "CLOSE", "case resolved", rec,
                        null))
                .andExpect(status().is3xxRedirection());
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.CLOSED);
        mockMvc.perform(get("/ui/cases/{id}/review", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "not ready for human review")));
        mockMvc.perform(reviewPost(caseId, "ESCALATE", "again", rec, null))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Review was not submitted")));
        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId))
                .hasSize(1);
    }

    // ---- CSRF ------------------------------------------------------------------

    @Test
    void stateChangingFormsRequireCsrf() throws Exception {
        String caseId = investigatedCase();
        String rec = recId(caseId);
        mockMvc.perform(intakePost().param("intent", "create"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ui/cases/{id}/investigate", caseId))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ui/cases/{id}/review", caseId)
                        .param("disposition", "ESCALATE")
                        .param("reason", "r")
                        .param("recommendationId", rec))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ui/cases/{id}/messages", caseId)
                        .param("message", "hello")
                        .param("clientReference", "x"))
                .andExpect(status().isForbidden());
        // API endpoints are JSON/stateless and remain CSRF-exempt by design.
        mockMvc.perform(post("/api/cases/{id}/reviews", caseId)
                        .contentType("application/json")
                        .content("{\"disposition\":\"ESCALATE\",\"reason\":\"r\","
                                + "\"recommendationId\":\"" + rec + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- follow-up (rules mode) -------------------------------------------------

    @Test
    void followUpIsUnavailableInRulesMode() throws Exception {
        String caseId = investigatedCase();
        mockMvc.perform(post("/ui/cases/{id}/messages", caseId)
                        .with(csrf())
                        .param("message", "can you close this case")
                        .param("clientReference", "UI-T1"))
                .andExpect(status().is3xxRedirection());
        assertThat(fraudCaseService.getCase(caseId).state())
                .isEqualTo(CaseState.READY_FOR_REVIEW);
        assertThat(reviewRepository
                .findByCaseIdOrderByRecordedAtAscReviewIdAsc(caseId)).isEmpty();
    }

    // ---- isolation ---------------------------------------------------------------

    @Test
    void caseBUiNeverShowsCaseAData() throws Exception {
        var base = TestFixtures.validRequest();
        String caseA = fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                base.transaction(),
                new FraudCaseIntakeRequest.ClaimDetails(
                        base.claim().issueType(),
                        "AAA-UNIQUE-CLAIM-TEXT case A only",
                        base.claim().cardPossession(),
                        base.claim().merchantRecognised()),
                base.consent())).caseId();
        String caseB = createCase();
        investigationService.investigate(caseA);
        investigationService.investigate(caseB);
        String recA = recId(caseA);

        mockMvc.perform(get("/ui/cases/{id}/evidence", caseB))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        not(containsString("AAA-UNIQUE-CLAIM-TEXT"))))
                .andExpect(content().string(not(containsString(caseA))));
        mockMvc.perform(get("/ui/cases/{id}/review", caseB))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(recA))))
                .andExpect(content().string(not(containsString(caseA))));
    }

    // ---- output encoding / template safety ---------------------------------------

    @Test
    void templatesContainNoUnescapedRendering() throws Exception {
        Path templates = Path.of("src/main/resources/templates");
        try (var stream = Files.walk(templates)) {
            for (Path file : stream.filter(p -> p.toString()
                    .endsWith(".html")).toList()) {
                String content = Files.readString(file);
                assertThat(content).as("unescaped output in " + file)
                        .doesNotContain("th:utext")
                        .doesNotContain("unescaped")
                        .doesNotContain("chainOfThought")
                        .doesNotContain("systemPrompt")
                        .doesNotContain("rawModelResponse")
                        .doesNotContain("internalReasoning");
            }
        }
    }

    @Test
    void renderedStatementIsEscaped() throws Exception {
        var base = TestFixtures.validRequest();
        String caseId = fraudCaseService.createCase(new FraudCaseIntakeRequest(
                base.reportedAt(), base.channel(), base.customer(),
                base.transaction(),
                new FraudCaseIntakeRequest.ClaimDetails(
                        base.claim().issueType(),
                        "I still have my card & my \"receipts\" are mine",
                        base.claim().cardPossession(),
                        base.claim().merchantRecognised()),
                base.consent())).caseId();
        mockMvc.perform(get("/ui/cases/{id}/evidence", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&amp;")))
                .andExpect(content().string(
                        not(containsString("card & my"))));
    }

    // ---- errors --------------------------------------------------------------------

    @Test
    void unknownCaseReturnsSafe404() throws Exception {
        mockMvc.perform(get("/ui/cases/{id}/investigation", "FC-DEADBEEF"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Not found")))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(
                        not(containsString("stacktrace"))));
    }
}
