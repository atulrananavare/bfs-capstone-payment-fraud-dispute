package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Case-scoped conversation queries only. Every read is bounded by
 * {@code caseId} — there is deliberately no unscoped findAll-based access
 * pattern for conversation data.
 */
public interface CaseMessageRepository extends JpaRepository<CaseMessageEntity, String> {

    List<CaseMessageEntity> findByCaseIdOrderBySequenceNumberAsc(String caseId);

    Optional<CaseMessageEntity> findTopByCaseIdOrderBySequenceNumberDesc(String caseId);

    Optional<CaseMessageEntity> findByCaseIdAndSequenceNumber(String caseId,
                                                            long sequenceNumber);

    Optional<CaseMessageEntity> findByCaseIdAndClientReference(String caseId,
                                                             String clientReference);

    long countByCaseId(String caseId);

    @Query("select distinct m.caseId from CaseMessageEntity m")
    List<String> findDistinctCaseIds();
}
