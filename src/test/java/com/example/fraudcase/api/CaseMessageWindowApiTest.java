package com.example.fraudcase.api;

import com.example.fraudcase.TestFixtures;
import com.example.fraudcase.agent.springai.StubChatModel;
import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.domain.AuditEventType;
import com.example.fraudcase.persistence.AuditEventAppender;
import com.example.fraudcase.persistence.AuditEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MEM06 — the active-window bound is configuration-driven, not hard-coded:
 * with {@code capstone.memory.max-messages=4} the model only ever sees the
 * latest four turns while the full record stays persisted.
 */
@SpringBootTest(properties = {
        "capstone.processor-mode=spring-ai",
        "capstone.memory.max-messages=4"})
@AutoConfigureMockMvc
class CaseMessageWindowApiTest {

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
    private ChatMemory chatMemory;

    @Autowired
    private AuditEventAppender auditAppender;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    private static String historyBlock(String promptText) {
        var m = Pattern.compile(
                        "<untrusted_conversation_history>(.*?)</untrusted_conversation_history>",
                        Pattern.DOTALL)
                .matcher(promptText);
        return m.find() ? m.group(1) : "";
    }

    @Test
    void configuredLimitOfFourBoundsActiveMemory() throws Exception {
        String caseId = fraudCaseService
                .createCase(TestFixtures.validRequest()).caseId();

        // Five exchanges = ten persisted turns.
        for (int i = 1; i <= 5; i++) {
            stub.enqueueJson("{\"caseId\":null,\"answer\":\"a" + i + "\","
                    + "\"evidenceIds\":[],\"policyCitations\":[],"
                    + "\"missingInformation\":[],\"humanReviewRequired\":true}");
            mockMvc.perform(post("/api/cases/{id}/messages", caseId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"q" + i + "\"}"))
                    .andExpect(status().isOk());
        }

        // Active Spring AI memory honours the configured bound.
        assertThat(chatMemory.get(caseId)).hasSize(4);
        assertThat(chatMemory.get(caseId).get(0).getText()).isEqualTo("q4");
        assertThat(chatMemory.get(caseId).get(3).getText()).isEqualTo("a5");

        // The prompt carried only the bounded window — the oldest turns
        // left the active context first.
        String history = historyBlock(
                stub.prompts().get(stub.prompts().size() - 1).getContents());
        assertThat(Pattern.compile("<turn role=").matcher(history)
                .results().count()).isEqualTo(4);
        assertThat(history).doesNotContain("q1")
                .contains("q4").contains("a4");

        // Audit marks the bound being applied.
        List<AuditEventType> types = auditAppender
                .findByCaseIdOrderByRecordedAt(caseId).stream()
                .map(AuditEventEntity::getEventType).toList();
        assertThat(types).contains(AuditEventType.CASE_MEMORY_LIMIT_APPLIED);
    }
}
