package com.example.fraudcase.domain;

/**
 * Category of an evidence item. {@code CUSTOMER_CLAIM} is declared by the
 * customer at intake and is never treated as independently observed fact;
 * all other types come from allow-listed evidence tools.
 */
public enum EvidenceType {

    CUSTOMER_CLAIM,
    TRANSACTION,
    DEVICE,
    LOCATION,
    MERCHANT,
    PRIOR_DISPUTE,
    SLA
}
