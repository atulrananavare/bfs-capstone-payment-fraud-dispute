package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudCaseRepository extends JpaRepository<FraudCaseEntity, String> {

    List<FraudCaseEntity> findAllByOrderByCreatedAtDesc();
}
