package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.FraudCase;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * JPA-backed {@link FraudCaseStore}. A case and its claim are persisted
 * atomically within the calling transaction.
 */
@Repository
public class JpaFraudCaseStore implements FraudCaseStore {

    private final FraudCaseRepository caseRepository;
    private final TransactionClaimRepository claimRepository;

    public JpaFraudCaseStore(FraudCaseRepository caseRepository,
                             TransactionClaimRepository claimRepository) {
        this.caseRepository = caseRepository;
        this.claimRepository = claimRepository;
    }

    @Override
    @Transactional
    public FraudCase save(FraudCase fraudCase) {
        FraudCaseEntity caseEntity = caseRepository.findById(fraudCase.caseId())
                .orElse(null);
        if (caseEntity == null) {
            caseRepository.save(FraudCaseMapper.toCaseEntity(fraudCase));
            claimRepository.save(FraudCaseMapper.toClaimEntity(fraudCase));
        } else {
            // Existing case: only the application-controlled state may change.
            caseEntity.setStatus(fraudCase.state());
            caseRepository.save(caseEntity);
        }
        return fraudCase;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FraudCase> findById(String caseId) {
        return caseRepository.findById(caseId)
                .flatMap(c -> claimRepository.findByCaseId(caseId)
                        .map(claim -> FraudCaseMapper.toDomain(c, claim)));
    }
}
