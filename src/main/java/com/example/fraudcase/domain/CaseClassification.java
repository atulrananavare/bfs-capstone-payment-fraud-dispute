package com.example.fraudcase.domain;

/**
 * Advisory classification of a fraud/dispute case. Recommendations only;
 * final disposition always belongs to a human reviewer.
 */
public enum CaseClassification {

    UNAUTHORISED_TRANSACTION,
    MERCHANT_DISPUTE,
    DUPLICATE_PROCESSING,
    SUSPICIOUS_TRANSACTION,
    INSUFFICIENT_EVIDENCE,
    GENUINE_TRANSACTION
}
