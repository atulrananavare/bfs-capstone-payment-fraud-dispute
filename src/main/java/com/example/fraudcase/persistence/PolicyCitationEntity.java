package com.example.fraudcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * One persisted policy citation attached to a validated recommendation.
 * {@code passage} stores the exact retrieved chunk text — the citation is
 * produced by the retrieval layer, never from model output.
 */
@Entity
@Table(name = "policy_citation")
public class PolicyCitationEntity {

    @Id
    @Column(name = "citation_id", length = 40)
    private String citationId;

    @Column(name = "recommendation_id", nullable = false, length = 40)
    private String recommendationId;

    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "section", nullable = false, length = 128)
    private String section;

    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "title", length = 160)
    private String title;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "passage", length = 4000)
    private String passage;

    protected PolicyCitationEntity() {
    }

    public PolicyCitationEntity(String citationId, String recommendationId,
                                String documentId, String version, String section,
                                String chunkId, double score, String title,
                                LocalDate effectiveDate, String passage) {
        this.citationId = citationId;
        this.recommendationId = recommendationId;
        this.documentId = documentId;
        this.version = version;
        this.section = section;
        this.chunkId = chunkId;
        this.score = score;
        this.title = title;
        this.effectiveDate = effectiveDate;
        this.passage = passage;
    }

    public String getCitationId() {
        return citationId;
    }

    public String getRecommendationId() {
        return recommendationId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getVersion() {
        return version;
    }

    public String getSection() {
        return section;
    }

    public String getChunkId() {
        return chunkId;
    }

    public double getScore() {
        return score;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getPassage() {
        return passage;
    }
}
