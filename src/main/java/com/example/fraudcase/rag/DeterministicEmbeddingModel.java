package com.example.fraudcase.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResultMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Offline deterministic {@link EmbeddingModel}: a hashed bag of canonical
 * concept tokens, L2-normalised over {@value #DIMENSIONS} dimensions.
 * Identical text always produces an identical vector, so the whole suite
 * runs without any external embedding provider or network access.
 *
 * <p>Raw vectors are never serialised — they exist only inside the store.
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 256;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        for (String instruction : request.getInstructions()) {
            embeddings.add(new Embedding(embed(instruction), index++,
                    EmbeddingResultMetadata.EMPTY));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    public float[] embed(String text) {
        Set<String> tokens = CanonicalTokenizer.tokenize(text);
        float[] vector = new float[DIMENSIONS];
        for (String token : tokens) {
            vector[bucket(token)] += 1.0f;
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    private static int bucket(String token) {
        // Stable 32-bit FNV-1a hash — deterministic across JVM runs.
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return Math.floorMod(hash, DIMENSIONS);
    }
}
