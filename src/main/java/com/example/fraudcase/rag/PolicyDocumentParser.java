package com.example.fraudcase.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the synthetic policy format: a {@code ---} fenced key/value front
 * matter followed by {@code ## }-headed sections. Pure string handling —
 * no framework dependencies, deterministic for identical input.
 */
public final class PolicyDocumentParser {

    public record Section(String heading, String body) {
    }

    public record ParsedPolicy(Map<String, String> frontMatter,
                               List<Section> sections) {
    }

    private PolicyDocumentParser() {
    }

    public static ParsedPolicy parse(String content) {
        if (content == null) {
            throw new IllegalArgumentException("policy content is null");
        }
        String[] lines = content.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].trim().equals("---")) {
            throw new IllegalArgumentException(
                    "policy document must start with a --- front-matter fence");
        }
        i++;
        Map<String, String> frontMatter = new LinkedHashMap<>();
        boolean closed = false;
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                closed = true;
                i++;
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue; // tolerate blank/comment-ish lines in the header
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"")
                    && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            frontMatter.put(key, value);
        }
        if (!closed) {
            throw new IllegalArgumentException("unterminated front matter");
        }

        List<Section> sections = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                if (heading != null) {
                    sections.add(new Section(heading, body.toString().strip()));
                }
                heading = line.substring(3).strip();
                if (heading.isEmpty()) {
                    throw new IllegalArgumentException(
                            "blank '## ' section heading");
                }
                body = new StringBuilder();
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            sections.add(new Section(heading, body.toString().strip()));
        }
        if (sections.isEmpty()) {
            throw new IllegalArgumentException(
                    "policy document has no '## ' sections");
        }
        return new ParsedPolicy(Map.copyOf(frontMatter), List.copyOf(sections));
    }
}
