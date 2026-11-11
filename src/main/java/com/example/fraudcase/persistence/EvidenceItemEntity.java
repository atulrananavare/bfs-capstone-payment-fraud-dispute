package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.EvidenceProvenance;
import com.example.fraudcase.domain.EvidenceSeverity;
import com.example.fraudcase.domain.EvidenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evidence_item")
public class EvidenceItemEntity {

    @Id
    @Column(name = "evidence_id", length = 64)
    private String evidenceId;

    @Column(name = "case_id", nullable = false, length = 32)
    private String caseId;

    @Column(name = "investigation_id", length = 40)
    private String investigationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 32)
    private EvidenceType evidenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private EvidenceSeverity severity;

    @Column(name = "value_summary", nullable = false, length = 500)
    private String valueSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 40)
    private EvidenceProvenance provenance;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    protected EvidenceItemEntity() {
    }

    public EvidenceItemEntity(String evidenceId, String caseId, String investigationId,
                              EvidenceType evidenceType, EvidenceSeverity severity,
                              String valueSummary, EvidenceProvenance provenance,
                              OffsetDateTime observedAt) {
        this.evidenceId = evidenceId;
        this.caseId = caseId;
        this.investigationId = investigationId;
        this.evidenceType = evidenceType;
        this.severity = severity;
        this.valueSummary = valueSummary;
        this.provenance = provenance;
        this.observedAt = observedAt;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public EvidenceSeverity getSeverity() {
        return severity;
    }

    public String getValueSummary() {
        return valueSummary;
    }

    public EvidenceProvenance getProvenance() {
        return provenance;
    }

    public OffsetDateTime getObservedAt() {
        return observedAt;
    }
}
