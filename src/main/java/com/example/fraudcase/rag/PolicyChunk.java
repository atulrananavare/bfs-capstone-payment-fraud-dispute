package com.example.fraudcase.rag;

/**
 * One indexed unit: a meaningful policy section (or a paragraph split of an
 * oversized section). {@code text} is the exact preserved passage and is
 * what becomes the citation body — it is never rewritten downstream.
 */
public record PolicyChunk(
        String chunkId,
        PolicyMetadata metadata,
        String section,
        int sectionIndex,
        int chunkIndex,
        String text) {
}
