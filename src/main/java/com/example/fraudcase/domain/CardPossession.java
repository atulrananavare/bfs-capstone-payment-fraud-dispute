package com.example.fraudcase.domain;

/**
 * Whether the customer still possesses the card associated with the dispute.
 */
public enum CardPossession {

    IN_POSSESSION,
    LOST,
    STOLEN,
    UNKNOWN
}
