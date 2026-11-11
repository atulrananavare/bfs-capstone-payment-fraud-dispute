package com.example.fraudcase.domain;

/**
 * Advisory operational route for a case. Conceptual default mappings used by
 * later phases:
 * <ul>
 *   <li>UNAUTHORISED_TRANSACTION / SUSPICIOUS_TRANSACTION -&gt; FRAUD_INVESTIGATION</li>
 *   <li>MERCHANT_DISPUTE -&gt; DISPUTE_OPERATIONS</li>
 *   <li>DUPLICATE_PROCESSING -&gt; PAYMENT_OPERATIONS</li>
 *   <li>INSUFFICIENT_EVIDENCE / GENUINE_TRANSACTION -&gt; MANUAL_REVIEW</li>
 * </ul>
 */
public enum RecommendedRoute {

    FRAUD_INVESTIGATION,
    DISPUTE_OPERATIONS,
    PAYMENT_OPERATIONS,
    MANUAL_REVIEW
}
