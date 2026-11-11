package com.example.fraudcase.persistence;

import com.example.fraudcase.domain.CaseState;
import com.example.fraudcase.domain.ReportChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "fraud_case")
public class FraudCaseEntity {

    @Id
    @Column(name = "case_id", length = 32)
    private String caseId;

    @Column(name = "masked_customer_ref", nullable = false, length = 64)
    private String maskedCustomerRef;

    @Column(name = "home_region", nullable = false, length = 100)
    private String homeRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_channel", nullable = false, length = 32)
    private ReportChannel reportChannel;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseState status;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected FraudCaseEntity() {
    }

    public FraudCaseEntity(String caseId, String maskedCustomerRef, String homeRegion,
                           ReportChannel reportChannel, OffsetDateTime reportedAt,
                           CaseState status, String createdBy, OffsetDateTime createdAt) {
        this.caseId = caseId;
        this.maskedCustomerRef = maskedCustomerRef;
        this.homeRegion = homeRegion;
        this.reportChannel = reportChannel;
        this.reportedAt = reportedAt;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getMaskedCustomerRef() {
        return maskedCustomerRef;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public ReportChannel getReportChannel() {
        return reportChannel;
    }

    public OffsetDateTime getReportedAt() {
        return reportedAt;
    }

    public CaseState getStatus() {
        return status;
    }

    public void setStatus(CaseState status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
