package com.example.fraudcase.rag;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trusted metadata registry of everything ever ingested in this process.
 * The retriever resolves "effective" versions strictly from this catalog —
 * never from vector-store payloads alone — and the agent resolves cited
 * chunk ids through it, so a model can only ever reference a real ingested
 * passage.
 */
@Component
public class PolicyCatalog {

    private final Map<String, PolicyChunk> chunksById = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PolicyMetadata>> versionsByDocument =
            new ConcurrentHashMap<>();

    public boolean contains(String chunkId) {
        return chunksById.containsKey(chunkId);
    }

    public void register(PolicyChunk chunk) {
        chunksById.put(chunk.chunkId(), chunk);
        versionsByDocument
                .computeIfAbsent(chunk.metadata().documentId(), k -> new ConcurrentHashMap<>())
                .put(chunk.metadata().version(), chunk.metadata());
    }

    public Optional<PolicyChunk> chunk(String chunkId) {
        return Optional.ofNullable(chunksById.get(chunkId));
    }

    /**
     * The single effective version for a document at {@code asOf}: the
     * highest CURRENT version whose effectiveDate is not in the future.
     * SUPERSEDED and WITHDRAWN versions are never effective.
     */
    public Optional<PolicyMetadata> effectiveVersion(String documentId,
                                                     LocalDate asOf) {
        Map<String, PolicyMetadata> versions = versionsByDocument.get(documentId);
        if (versions == null) {
            return Optional.empty();
        }
        return versions.values().stream()
                .filter(m -> m.status() == PolicyMetadata.Status.CURRENT)
                .filter(m -> !m.effectiveDate().isAfter(asOf))
                .max(Comparator.comparing(PolicyMetadata::version, PolicyCatalog::versionOrder));
    }

    public boolean isEffective(String documentId, String version, LocalDate asOf) {
        return effectiveVersion(documentId, asOf)
                .map(m -> m.version().equals(version))
                .orElse(false);
    }

    public Map<String, PolicyChunk> snapshot() {
        return new LinkedHashMap<>(chunksById);
    }

    private static int versionOrder(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? parsePart(pa[i]) : 0;
            int y = i < pb.length ? parsePart(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
