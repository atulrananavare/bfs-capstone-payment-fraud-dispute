package com.example.fraudcase.domain;

/**
 * Thrown when application/domain logic rejects an illegal case-state
 * transition.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(CaseState from, CaseState to) {
        super("Illegal case state transition: " + from + " -> " + to);
    }
}
