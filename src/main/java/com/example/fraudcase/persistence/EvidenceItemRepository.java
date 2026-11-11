package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItemEntity, String> {

    List<EvidenceItemEntity> findByCaseIdOrderByObservedAt(String caseId);
}
