package com.example.fraudcase.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised capstone configuration ({@code capstone.*}).
 *
 * <p>Safety invariants are enforced at startup: the context fails to start
 * if {@code human-review-required} is not {@code true}, or if financial
 * actions or customer contact are enabled. Code cannot silently override
 * them to unsafe values.
 */
@Validated
@ConfigurationProperties(prefix = "capstone")
public class CapstoneProperties {

    /**
     * Analysis implementation: {@code rules} (deterministic fallback) or
     * {@code spring-ai} (primary adapter, later phase).
     */
    @NotNull
    private ProcessorMode processorMode = ProcessorMode.RULES;

    @AssertTrue(message = "capstone.human-review-required must always be true")
    private boolean humanReviewRequired = true;

    @AssertFalse(message = "capstone.allow-financial-actions must always be false: autonomous financial actions are forbidden")
    private boolean allowFinancialActions = false;

    @AssertFalse(message = "capstone.allow-customer-contact must always be false: autonomous customer contact is forbidden")
    private boolean allowCustomerContact = false;

    @Valid
    @NotNull
    private Retrieval retrieval = new Retrieval();

    @Valid
    @NotNull
    private Memory memory = new Memory();

    @Valid
    @NotNull
    private Tools tools = new Tools();

    public enum ProcessorMode {
        RULES,
        SPRING_AI
    }

    public static class Retrieval {

        /** Top-K policy passages to retrieve. */
        @Min(1)
        @Max(20)
        private int topK = 4;

        /** Minimum similarity score for a policy passage to be usable. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double similarityThreshold = 0.72;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }

    public static class Memory {

        /** Bounded case-keyed conversation retention. */
        @Min(1)
        @Max(200)
        private int maxMessages = 12;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }

    public static class Tools {

        /** Per-tool invocation timeout. */
        @Min(100)
        @Max(60_000)
        private long timeoutMs = 2500;

        /** Seed for deterministic synthetic evidence generation. */
        @NotBlank
        private String deterministicSeed = "fraud-training-v1";

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getDeterministicSeed() {
            return deterministicSeed;
        }

        public void setDeterministicSeed(String deterministicSeed) {
            this.deterministicSeed = deterministicSeed;
        }
    }

    public ProcessorMode getProcessorMode() {
        return processorMode;
    }

    public void setProcessorMode(ProcessorMode processorMode) {
        this.processorMode = processorMode;
    }

    public boolean isHumanReviewRequired() {
        return humanReviewRequired;
    }

    public void setHumanReviewRequired(boolean humanReviewRequired) {
        this.humanReviewRequired = humanReviewRequired;
    }

    public boolean isAllowFinancialActions() {
        return allowFinancialActions;
    }

    public void setAllowFinancialActions(boolean allowFinancialActions) {
        this.allowFinancialActions = allowFinancialActions;
    }

    public boolean isAllowCustomerContact() {
        return allowCustomerContact;
    }

    public void setAllowCustomerContact(boolean allowCustomerContact) {
        this.allowCustomerContact = allowCustomerContact;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools;
    }
}
