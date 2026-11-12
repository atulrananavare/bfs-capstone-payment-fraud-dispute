package com.example.fraudcase.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunks a parsed policy by meaning: one chunk per {@code ## } section,
 * split further on blank-line paragraphs only when a section exceeds
 * {@link #MAX_CHUNK_CHARS}. Chunk ids are stable —
 * {@code <documentId>-<version>-S<nn>-C<mm>} — so repeated ingestion of the
 * same source is idempotent.
 */
public final class PolicyChunker {

    static final int MAX_CHUNK_CHARS = 1200;

    private PolicyChunker() {
    }

    public static List<PolicyChunk> chunk(PolicyMetadata metadata,
                                          List<PolicyDocumentParser.Section> sections) {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            PolicyDocumentParser.Section section = sections.get(s);
            String full = "## " + section.heading() + "\n\n" + section.body();
            List<String> parts;
            if (full.length() <= MAX_CHUNK_CHARS) {
                parts = List.of(full.strip());
            } else {
                parts = splitOnParagraphs(section.heading(), section.body());
            }
            for (int c = 0; c < parts.size(); c++) {
                chunks.add(new PolicyChunk(
                        chunkId(metadata, s, c),
                        metadata,
                        section.heading(),
                        s + 1,
                        c + 1,
                        parts.get(c)));
            }
        }
        return List.copyOf(chunks);
    }

    static String chunkId(PolicyMetadata metadata, int sectionIndex, int chunkIndex) {
        return String.format("%s-%s-S%02d-C%02d",
                metadata.documentId(), metadata.version(),
                sectionIndex + 1, chunkIndex + 1);
    }

    private static List<String> splitOnParagraphs(String heading, String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder("## " + heading + "\n\n");
        for (String para : body.split("\n\\s*\n")) {
            if (current.length() + para.length() > MAX_CHUNK_CHARS
                    && current.length() > heading.length() + 6) {
                parts.add(current.toString().strip());
                current = new StringBuilder("## " + heading + " (cont.)\n\n");
            }
            current.append(para.strip()).append("\n\n");
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }
}
