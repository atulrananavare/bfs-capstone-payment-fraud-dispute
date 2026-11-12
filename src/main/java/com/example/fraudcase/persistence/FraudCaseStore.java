package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.FraudCase;

import java.util.List;
import java.util.Optional;

/**
 * Application-facing case store contract. The JPA implementation is the
 * current backing; the interface keeps services decoupled from the database
 * product.
 */
public interface FraudCaseStore {

    FraudCase save(FraudCase fraudCase);

    Optional<FraudCase> findById(String caseId);

    /** All cases, newest first — read-only analyst worklist. */
    List<FraudCase> findAllRecent();
}
