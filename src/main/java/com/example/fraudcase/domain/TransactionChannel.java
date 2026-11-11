package com.example.fraudcase.domain;

/**
 * Channel in which the disputed transaction was performed.
 */
public enum TransactionChannel {

    CARD_PRESENT,
    CARD_NOT_PRESENT,
    ATM,
    BANK_TRANSFER,
    OTHER
}
