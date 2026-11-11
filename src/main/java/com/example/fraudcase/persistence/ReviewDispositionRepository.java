package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewDispositionRepository extends JpaRepository<ReviewDispositionEntity, String> {

    List<ReviewDispositionEntity> findByCaseId(String caseId);
}
