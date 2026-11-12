package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewDispositionRepository extends JpaRepository<ReviewDispositionEntity, String> {

    List<ReviewDispositionEntity> findByCaseIdOrderByRecordedAtAscReviewIdAsc(String caseId);

    Optional<ReviewDispositionEntity> findByCaseIdAndClientReference(String caseId,
                                                                   String clientReference);

    boolean existsByRecommendationId(String recommendationId);
}
