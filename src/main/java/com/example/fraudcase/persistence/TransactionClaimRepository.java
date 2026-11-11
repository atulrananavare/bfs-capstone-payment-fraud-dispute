package com.example.fraudcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionClaimRepository extends JpaRepository<TransactionClaimEntity, String> {

    Optional<TransactionClaimEntity> findByCaseId(String caseId);
}
