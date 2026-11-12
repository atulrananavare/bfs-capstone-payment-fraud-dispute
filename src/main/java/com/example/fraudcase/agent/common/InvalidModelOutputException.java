package com.example.fraudcase.agent.common;

/**
 * The model produced output that cannot be mapped to a valid
 * {@code FraudCaseRecommendation}/{@code FollowUpAnswer}: malformed JSON,
 * unknown enum values, out-of-range confidence, fabricated policy citations
 * or broken domain invariants. Framework-neutral — every AI adapter throws
 * this same type so the application layer rejects output uniformly.
 * Audited as output rejection and never persisted as-is.
 */
public class InvalidModelOutputException extends ModelProcessingException {

    public InvalidModelOutputException(String message) {
        super(message);
    }

    public InvalidModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
