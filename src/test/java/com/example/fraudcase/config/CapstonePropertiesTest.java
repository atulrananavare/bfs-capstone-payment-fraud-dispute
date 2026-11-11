package com.example.fraudcase.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapstonePropertiesTest {

    @Test
    void safetyDefaultsAreSecure() {
        CapstoneProperties properties = new CapstoneProperties();

        assertThat(properties.getProcessorMode())
                .isEqualTo(CapstoneProperties.ProcessorMode.RULES);
        assertThat(properties.isHumanReviewRequired()).isTrue();
        assertThat(properties.isAllowFinancialActions()).isFalse();
        assertThat(properties.isAllowCustomerContact()).isFalse();
    }

    @Test
    void retrievalAndToolDefaultsMatchCapstoneTargets() {
        CapstoneProperties properties = new CapstoneProperties();

        assertThat(properties.getRetrieval().getTopK()).isEqualTo(4);
        assertThat(properties.getRetrieval().getSimilarityThreshold()).isEqualTo(0.72);
        assertThat(properties.getMemory().getMaxMessages()).isEqualTo(12);
        assertThat(properties.getTools().getTimeoutMs()).isEqualTo(2500);
        assertThat(properties.getTools().getDeterministicSeed()).isEqualTo("fraud-training-v1");
    }
}
