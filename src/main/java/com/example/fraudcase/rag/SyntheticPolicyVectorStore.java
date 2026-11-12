package com.example.fraudcase.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory {@link VectorStore} implementation for the
 * synthetic policy corpus.
 *
 * <p>Every document is embedded through the configured
 * {@link EmbeddingModel} (default: {@link DeterministicEmbeddingModel}) at
 * ingest time — real embedding generation, just offline. Similarity is a
 * concept-coverage score over the canonical token sets: the fraction of
 * query concepts that appear in the candidate. This makes the
 * threshold/top-K semantics deterministic and independently testable —
 * a sparse bag-of-words cosine cannot produce the calibrated scores the
 * safety rules require (irrelevant text must score ~0).
 *
 * <p>Idempotent: re-adding the same document id overwrites. Replaceable:
 * any other {@link VectorStore} bean takes precedence in configuration.
 */
public class SyntheticPolicyVectorStore implements VectorStore {

    private record Entry(Document document, Set<String> tokens, float[] vector) {
    }

    private final EmbeddingModel embeddingModel;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public SyntheticPolicyVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        Assert.notNull(documents, "documents must not be null");
        for (Document document : documents) {
            store.put(document.getId(), new Entry(
                    document,
                    CanonicalTokenizer.tokenize(document.getText()),
                    embeddingModel.embed(document)));
        }
    }

    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            store.remove(id);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException(
                "filter deletion is not supported by the synthetic store");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        Set<String> queryTokens = CanonicalTokenizer.tokenize(request.getQuery());
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : store.values()) {
            scored.add(new Scored(entry, coverage(queryTokens, entry.tokens()),
                    sharedCount(queryTokens, entry.tokens())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingInt(Scored::shared).reversed())
                .thenComparing(s -> s.entry().document().getId()));
        List<Document> results = new ArrayList<>();
        int limit = Math.max(request.getTopK(), 0);
        for (Scored s : scored) {
            if (results.size() >= limit) {
                break;
            }
            if (s.score() < request.getSimilarityThreshold()) {
                continue;
            }
            results.add(s.entry().document().mutate()
                    .score(s.score()).build());
        }
        return List.copyOf(results);
    }

    public Optional<Document> get(String id) {
        return Optional.ofNullable(store.get(id)).map(Entry::document);
    }

    public int size() {
        return store.size();
    }

    /** Exposed only for failure-path testing. */
    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    /**
     * Saturated concept coverage: |q ∩ d| / min(|q|, {@link #SATURATION}).
     * A passage covering {@value #SATURATION} or more distinct query
     * concepts is fully relevant for this small synthetic corpus — without
     * saturation, realistic 15-25 token investigation queries could never
     * reach the required similarity threshold even for the correct policy.
     * Capped at 1.0; ordering between saturated hits falls back to the raw
     * shared-concept count, then chunk id for determinism.
     */
    private static final int SATURATION = 8;

    private static double coverage(Set<String> query, Set<String> doc) {
        long shared = query.stream().filter(doc::contains).count();
        return Math.min(1.0, (double) shared / Math.min(query.size(), SATURATION));
    }

    private static int sharedCount(Set<String> query, Set<String> doc) {
        return (int) query.stream().filter(doc::contains).count();
    }

    private record Scored(Entry entry, double score, int shared) {
    }
}
