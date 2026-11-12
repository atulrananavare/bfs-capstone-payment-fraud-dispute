package com.example.fraudcase.agent.springai;

/**
 * The model produced output that cannot be mapped to a valid
 * {@code FraudCaseRecommendation}: malformed JSON, unknown enum values,
 * out-of-range confidence, fabricated policy citations or broken domain
 * invariants. Triggers the deterministic rules fallback upstream and is
 * audited as output rejection — never persisted as-is.
 */
public class InvalidModelOutputException extends SpringAiProcessingException {

    public InvalidModelOutputException(String message) {
        super(message);
    }

    public InvalidModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
