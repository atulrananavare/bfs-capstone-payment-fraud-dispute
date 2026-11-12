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

    @Valid
    @NotNull
    private Ai ai = new Ai();

    @Valid
    @NotNull
    private Review review = new Review();

    public enum ProcessorMode {
        RULES,
        SPRING_AI
    }

    /**
     * Bounds for the Spring AI adapter (Phase 3). None of these apply in
     * rules mode — no model, provider or credential is touched there.
     */
    public static class Ai {

        /** Bound on one model invocation (connect/read timeout). */
        @Min(1000)
        @Max(120_000)
        private long timeoutMs = 10_000;

        /** Sampling temperature sent to the provider; 0 keeps output stable. */
        @DecimalMin("0.0")
        @DecimalMax("2.0")
        private double temperature = 0.0;

        /** Maximum model-driven tool calls allowed per investigation. */
        @Min(1)
        @Max(50)
        private int maxToolCalls = 6;

        /** Maximum model round-trips (tool-call loop iterations) per run. */
        @Min(1)
        @Max(16)
        private int maxRounds = 4;

        /** Upper bound on generated tokens for the structured answer. */
        @Min(256)
        @Max(8192)
        private int maxOutputTokens = 1024;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public static class Retrieval {

        /** Master switch for the RAG infrastructure. When false, no
         *  embedding/vector/retrieval beans exist and every processor sees
         *  policy as not requested (rules) or unavailable (spring-ai). */
        private boolean enabled = true;

        /** Top-K policy passages to retrieve. */
        @Min(1)
        @Max(20)
        private int topK = 4;

        /** Minimum similarity score for a policy passage to be usable. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double similarityThreshold = 0.72;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

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

    /**
     * Synthetic training reviewer identity (Phase 6). The values are
     * application-supplied — never read from request payloads. When a real
     * security context arrives, {@code ReviewerIdentityProvider} is swapped
     * for an authenticated implementation and these fields are ignored.
     */
    public static class Review {

        /** Synthetic reviewer id used until authenticated identity exists. */
        @NotBlank
        private String actorId = "TRAINING-REVIEWER-001";

        /** Synthetic role resolved for the training reviewer. */
        @NotNull
        private com.example.fraudcase.domain.ReviewerRole actorRole =
                com.example.fraudcase.domain.ReviewerRole.REVIEWER;

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }

        public com.example.fraudcase.domain.ReviewerRole getActorRole() {
            return actorRole;
        }

        public void setActorRole(com.example.fraudcase.domain.ReviewerRole actorRole) {
            this.actorRole = actorRole;
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

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Review getReview() {
        return review;
    }

    public void setReview(Review review) {
        this.review = review;
    }
}
