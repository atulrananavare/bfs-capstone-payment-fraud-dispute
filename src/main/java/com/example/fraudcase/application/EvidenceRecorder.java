package com.example.fraudcase.application;

import com.example.fraudcase.domain.EvidenceItem;
import com.example.fraudcase.persistence.EvidenceItemEntity;
import com.example.fraudcase.persistence.EvidenceItemRepository;
import org.springframework.stereotype.Component;

/**
 * Single write path for evidence items. Content-derived ids make re-runs
 * idempotent — an identical fact is never duplicated.
 */
@Component
public class EvidenceRecorder {

    private final EvidenceItemRepository evidenceRepository;

    public EvidenceRecorder(EvidenceItemRepository evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    public void record(EvidenceItem item, String investigationId) {
        if (evidenceRepository.existsById(item.evidenceId())) {
            return;
        }
        evidenceRepository.save(new EvidenceItemEntity(
                item.evidenceId(), item.caseId(), investigationId,
                item.type(), item.severity(), item.valueSummary(),
                item.provenance(), item.observedAt()));
    }
}
