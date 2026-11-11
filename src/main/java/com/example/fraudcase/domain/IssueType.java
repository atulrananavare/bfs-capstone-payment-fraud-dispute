package com.example.fraudcase.domain;

/**
 * The customer's declared reason for the dispute.
 */
public enum IssueType {

    MERCHANT_NOT_RECOGNISED,
    UNAUTHORISED_TRANSACTION,
    DUPLICATE_CHARGE,
    AMOUNT_DISPUTE,
    CANCELLED_SERVICE,
    NON_RECEIPT,
    OTHER
}
