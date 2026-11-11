package com.example.fraudcase;

import com.example.fraudcase.config.CapstoneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FraudCaseAgentApplicationTests {

    @Autowired
    private CapstoneProperties capstoneProperties;

    @Test
    void contextLoadsWithoutAiProviderOrVectorStore() {
        // Phase 1: no model provider, vector store or credentials are needed.
    }

    @Test
    void boundSafetyConfigurationIsSecure() {
        assertThat(capstoneProperties.isHumanReviewRequired()).isTrue();
        assertThat(capstoneProperties.isAllowFinancialActions()).isFalse();
        assertThat(capstoneProperties.isAllowCustomerContact()).isFalse();
    }
}
