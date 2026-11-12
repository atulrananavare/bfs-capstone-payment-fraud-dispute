package com.example.fraudcase.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.DefaultEmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline embedding model: identical input must produce an identical
 * vector, unit-normalised, with the canonical vocabulary making
 * paraphrases overlap (seller≈merchant, unfamiliar≈unrecognised).
 */
class DeterministicEmbeddingModelTest {

    private final DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

    @Test
    void identicalTextProducesIdenticalVector() {
        float[] a = model.embed("card not present transaction");
        float[] b = model.embed("card not present transaction");
        assertThat(a).containsExactly(b);
    }

    @Test
    void vectorsAreUnitNormalised() {
        float[] v = model.embed("merchant dispute cancellation refund");
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        assertThat(norm).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(v).hasSize(DeterministicEmbeddingModel.DIMENSIONS);
    }

    @Test
    void synonymCanonicalisationCreatesOverlap() {
        float[] formal = model.embed("customer denies transaction merchant not recognised");
        float[] paraphrase = model.embed("customer says the seller name is unfamiliar");
        double dot = 0;
        for (int i = 0; i < formal.length; i++) {
            dot += formal[i] * paraphrase[i];
        }
        assertThat(dot).isGreaterThan(0.3);
    }

    @Test
    void differentTextProducesDifferentVector() {
        assertThat(model.embed("duplicate posting fingerprint"))
                .isNotEqualTo(model.embed("office cafeteria lunch menu"));
    }

    @Test
    void embeddingRequestProducesResponse() {
        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("a", "b"), new DefaultEmbeddingOptions()));
        assertThat(response.getResults()).hasSize(2);
    }
}
