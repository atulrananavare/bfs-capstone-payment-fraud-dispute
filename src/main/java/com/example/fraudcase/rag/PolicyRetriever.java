package com.example.fraudcase.rag;

import com.example.fraudcase.domain.PolicyPassage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The approved {@code retrievePolicyEvidence} capability: read-only,
 * case-bound, bounded and deterministic.
 *
 * <p>Pipeline: over-fetch from the {@link VectorStore} → reject anything
 * below the configured similarity threshold in application code → drop
 * documents that are not the effective current version per the
 * {@link PolicyCatalog} (superseded / withdrawn / future-dated) →
 * deterministic ordering → top-K. Nothing is returned unless it survives
 * every filter; an empty result is an explicit NO_MATCH abstention.
 */
public class PolicyRetriever {

    private static final Logger log =
            LoggerFactory.getLogger(PolicyRetriever.class);

    private final VectorStore vectorStore;
    private final PolicyCatalog catalog;
    private final int topK;
    private final double similarityThreshold;
    private final Clock clock;

    public PolicyRetriever(VectorStore vectorStore,
                           PolicyCatalog catalog,
                           int topK,
                           double similarityThreshold,
                           Clock clock) {
        this.vectorStore = vectorStore;
        this.catalog = catalog;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.clock = clock;
    }

    /** The allow-listed read-only retrieval capability. */
    public PolicyRetrievalOutcome retrievePolicyEvidence(
            PolicyRetrievalRequest request) {
        String query = PolicyQueryBuilder.queryText(request);
        String queryHash = sha256(query);
        int candidateCount = 0;
        try {
            List<Document> candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(Math.max(topK * 4, 16))
                            .similarityThreshold(
                                    SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                            .build());
            candidateCount = candidates.size();
            LocalDate asOf = LocalDate.now(clock);
            List<PolicyPassage> kept = new ArrayList<>();
            // Candidates arrive pre-sorted by the store
            // (score desc, shared-concept count desc, chunk id) — preserving
            // that order keeps the most covering passage first.
            for (Document doc : candidates) {
                if (kept.size() >= topK) {
                    break;
                }
                double score = doc.getScore() == null ? 0.0 : doc.getScore();
                if (score < similarityThreshold) {
                    continue; // threshold enforced in application code
                }
                PolicyChunk chunk = catalog.chunk(doc.getId()).orElse(null);
                if (chunk == null) {
                    continue; // never trust a result the catalog cannot resolve
                }
                PolicyMetadata m = chunk.metadata();
                if (m.status() != PolicyMetadata.Status.CURRENT
                        || m.effectiveDate().isAfter(asOf)
                        || !catalog.isEffective(m.documentId(), m.version(), asOf)) {
                    continue; // superseded / withdrawn / future / stale version
                }
                kept.add(new PolicyPassage(m.documentId(), m.title(),
                        m.version(), m.effectiveDate(), m.owner(),
                        chunk.section(), chunk.chunkId(), score, chunk.text()));
            }
            List<PolicyPassage> result = List.copyOf(kept);
            log.info("POLICY_RETRIEVAL queryHash={} candidates={} kept={}",
                    queryHash, candidateCount, result.size());
            if (result.isEmpty()) {
                return PolicyRetrievalOutcome.noMatch(
                        "sha256:" + queryHash, candidateCount);
            }
            return PolicyRetrievalOutcome.retrieved(result,
                    "sha256:" + queryHash, candidateCount);
        } catch (RuntimeException ex) {
            log.error("POLICY_RETRIEVAL_FAILED type={}",
                    ex.getClass().getSimpleName());
            return PolicyRetrievalOutcome.unavailable(
                    ex.getClass().getSimpleName());
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return "unhashed";
        }
    }
}
