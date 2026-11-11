package com.example.fraudcase.agent.common;

import com.example.fraudcase.domain.FraudCaseContext;
import com.example.fraudcase.domain.FraudCaseRecommendation;

/**
 * Framework-neutral processor contract. Every analysis implementation —
 * the deterministic rules adapter and the future Spring AI adapter — must
 * produce the same typed output and respect the same invariants.
 *
 * <p>Implementations must never execute financial, account-control or
 * customer-contacting actions, and every returned recommendation must carry
 * {@code humanReviewRequired = true}.
 */
public interface FraudCaseProcessor {

    FraudCaseRecommendation analyze(FraudCaseContext context);
}
