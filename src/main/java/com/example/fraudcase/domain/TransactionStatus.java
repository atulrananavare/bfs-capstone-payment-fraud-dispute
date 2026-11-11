package com.example.fraudcase.domain;

/**
 * Posting state of the disputed transaction at intake time.
 */
public enum TransactionStatus {

    PENDING,
    POSTED,
    SETTLED,
    DECLINED,
    REVERSED
}
