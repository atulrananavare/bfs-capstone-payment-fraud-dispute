package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<RecommendationEntity, String> {

    Optional<RecommendationEntity> findTopByCaseIdOrderByCreatedAtDesc(String caseId);

    List<RecommendationEntity> findByCaseIdOrderByCreatedAtDesc(String caseId);
}
