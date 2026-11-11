package com.example.fraudcase.domain;

/**
 * Where a piece of evidence originated. Every material evidence item must
 * carry provenance.
 */
public enum EvidenceProvenance {

    CUSTOMER_INTAKE,
    TRANSACTION_TOOL,
    DEVICE_TOOL,
    LOCATION_TOOL,
    MERCHANT_TOOL,
    PRIOR_DISPUTE_TOOL,
    SLA_TOOL
}
