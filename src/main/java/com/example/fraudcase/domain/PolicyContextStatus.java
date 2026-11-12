package com.example.fraudcase.domain;

/**
 * Whether policy grounding was requested for this investigation and what
 * came back. Drives the {@code R005} policy-gap rule and the
 * no-match abstention behaviour.
 */
public enum PolicyContextStatus {

    /** Retrieval not applicable (rules mode never requests it). */
    NOT_REQUESTED,

    /** At least one valid current passage passed all filters. */
    RETRIEVED,

    /** Retrieval ran but nothing passed threshold/version/effective-date. */
    NO_MATCH,

    /** Retrieval infrastructure was unavailable or failed. */
    UNAVAILABLE
}
