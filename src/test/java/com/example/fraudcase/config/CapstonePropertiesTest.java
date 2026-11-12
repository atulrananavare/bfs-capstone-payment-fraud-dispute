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

    @Test
    void invalidRetrievalConfigurationFailsValidation() {
        var validator = jakarta.validation.Validation
                .buildDefaultValidatorFactory().getValidator();

        CapstoneProperties zeroTopK = new CapstoneProperties();
        zeroTopK.getRetrieval().setTopK(0);
        assertThat(validator.validate(zeroTopK)).isNotEmpty();

        CapstoneProperties negativeTopK = new CapstoneProperties();
        negativeTopK.getRetrieval().setTopK(-2);
        assertThat(validator.validate(negativeTopK)).isNotEmpty();

        CapstoneProperties negativeThreshold = new CapstoneProperties();
        negativeThreshold.getRetrieval().setSimilarityThreshold(-0.1);
        assertThat(validator.validate(negativeThreshold)).isNotEmpty();

        CapstoneProperties aboveOneThreshold = new CapstoneProperties();
        aboveOneThreshold.getRetrieval().setSimilarityThreshold(1.5);
        assertThat(validator.validate(aboveOneThreshold)).isNotEmpty();
    }
}
