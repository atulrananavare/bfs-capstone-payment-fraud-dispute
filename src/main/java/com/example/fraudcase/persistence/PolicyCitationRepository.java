package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyCitationRepository extends JpaRepository<PolicyCitationEntity, String> {

    List<PolicyCitationEntity> findByRecommendationId(String recommendationId);
}
