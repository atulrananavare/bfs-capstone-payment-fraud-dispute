package com.example.fraudcase.application;

import com.example.fraudcase.domain.CaseState;

/**
 * Thrown when an investigation is requested for a case whose state does not
 * permit (re)starting one.
 */
public class InvestigationNotPermittedException extends RuntimeException {

    public InvestigationNotPermittedException(String caseId, CaseState state) {
        super("Investigation cannot start for case " + caseId + " in state " + state);
    }
}
