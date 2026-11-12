package com.example.fraudcase.rag;

import com.example.fraudcase.domain.PolicyCitation;
import com.example.fraudcase.domain.PolicyPassage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval: top-K, threshold, version/effective-date/status filtering and
 * abstention — all against the real ingested synthetic policy pack, fully
 * offline.
 */
class PolicyRetrieverTest {

    private static final double THRESHOLD = 0.72;

    private SyntheticPolicyVectorStore store;
    private PolicyCatalog catalog;
    private PolicyRetriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        store = new SyntheticPolicyVectorStore(new DeterministicEmbeddingModel());
        catalog = new PolicyCatalog();
        new PolicyIngestionService(store, catalog).ingest(Arrays.asList(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath:policies/*.md")));
        retriever = new PolicyRetriever(store, catalog, 4, THRESHOLD,
                Clock.systemDefaultZone());
    }

    private PolicyRetrievalRequest request(String... descriptors) {
        return new PolicyRetrievalRequest("FC-RAG01",
                List.of(descriptors),
                "determine correct triage routing and required evidence");
    }

    private PolicyRetrievalRequest standardCnpRequest() {
        return request(
                "card not present transaction",
                "customer denies unauthorised transaction",
                "merchant not recognised",
                "authentication outcome unavailable",
                "new unknown device",
                "untrusted device",
                "high location anomaly");
    }

    @Test
    void rag04_standardCnpQueryFindsTheCnpRoutingSection() {
        PolicyRetrievalOutcome outcome =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.RETRIEVED);
        PolicyPassage top = outcome.passages().get(0);
        assertThat(top.documentId()).isEqualTo("POL-CNP-017");
        assertThat(top.version()).isEqualTo("3.2");
        assertThat(top.section()).isEqualTo("3.2 Initial fraud routing");
        assertThat(top.chunkId()).isEqualTo("POL-CNP-017-3.2-S04-C01");
        assertThat(top.score()).isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(top.passage()).contains("fraud investigation");
    }

    @Test
    void rag05_paraphrasedMerchantQueryFindsMerchantPolicy() {
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                request("customer says the seller name is unfamiliar",
                        "merchant recognised by customer",
                        "merchant dispute complaint",
                        "service cancelled but charged",
                        "fulfilment cancellation dispute operations"));
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.RETRIEVED);
        assertThat(outcome.passages().get(0).documentId()).isEqualTo("POL-MRD-004");
    }

    @Test
    void rag06_irrelevantQueryAbstainsWithNoMatch() {
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                request("office cafeteria lunch menu",
                        "holiday party seating chart"));
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.NO_MATCH);
        assertThat(outcome.passages()).isEmpty();
        assertThat(outcome.candidatesEvaluated()).isGreaterThan(0);
    }

    @Test
    void rag07_belowThresholdResultsAreRejectedEvenWhenReturned() {
        // A generic query shares a few concepts (score ~0.5) — the store
        // returns candidates, but application code must drop them.
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                request("posted transaction"));
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.NO_MATCH);
        assertThat(outcome.candidatesEvaluated()).isGreaterThan(0);
    }

    @Test
    void thresholdIsInclusiveAtTheConfiguredBoundary() {
        // Documented semantics: score >= threshold is kept; score < threshold
        // is dropped — enforced in application code, not the store.
        Document real = store.get("POL-CNP-017-3.2-S04-C01").orElseThrow();
        VectorStore atBoundary = stubStore(List.of(
                real.mutate().score(THRESHOLD).build()));
        VectorStore justBelow = stubStore(List.of(
                real.mutate().score(THRESHOLD - 0.001).build()));

        assertThat(new PolicyRetriever(atBoundary, catalog, 4, THRESHOLD,
                Clock.systemDefaultZone())
                .retrievePolicyEvidence(standardCnpRequest()).passages())
                .hasSize(1);
        assertThat(new PolicyRetriever(justBelow, catalog, 4, THRESHOLD,
                Clock.systemDefaultZone())
                .retrievePolicyEvidence(standardCnpRequest()).status())
                .isEqualTo(PolicyRetrievalOutcome.Status.NO_MATCH);
    }

    private static VectorStore stubStore(List<Document> results) {
        return new VectorStore() {
            @Override
            public void add(List<Document> documents) {
            }

            @Override
            public void delete(List<String> idList) {
            }

            @Override
            public void delete(Filter.Expression filterExpression) {
            }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                return results;
            }
        };
    }

    @Test
    void rag08_topKBoundsTheResultSet() {
        PolicyRetriever top1 = new PolicyRetriever(store, catalog, 1,
                THRESHOLD, Clock.systemDefaultZone());
        PolicyRetrievalOutcome outcome =
                top1.retrievePolicyEvidence(standardCnpRequest());
        assertThat(outcome.passages()).hasSize(1);
        assertThat(outcome.passages().get(0).chunkId())
                .isEqualTo("POL-CNP-017-3.2-S04-C01");
    }

    @Test
    void rag09_supersededVersionNeverReturnedEvenWhenItMatches() {
        // Ingest ONLY the superseded v3.1 into a fresh store: its wording
        // scores, but the catalog marks it SUPERSEDED — it must be filtered.
        SyntheticPolicyVectorStore onlyOld =
                new SyntheticPolicyVectorStore(new DeterministicEmbeddingModel());
        PolicyCatalog oldCatalog = new PolicyCatalog();
        new PolicyIngestionService(onlyOld, oldCatalog).ingest(List.of(
                new org.springframework.core.io.ClassPathResource(
                        "policies/POL-CNP-017-v3.1.md")));
        PolicyRetriever oldOnly = new PolicyRetriever(onlyOld, oldCatalog,
                4, 0.30, Clock.systemDefaultZone());
        PolicyRetrievalOutcome outcome =
                oldOnly.retrievePolicyEvidence(request(
                        "customer disputes a remote purchase",
                        "fraud investigation analyst discretion"));
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.NO_MATCH);

        // And with the full pack, no passage may carry version 3.1.
        PolicyRetrievalOutcome full =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        assertThat(full.passages())
                .noneMatch(p -> "3.1".equals(p.version()));
    }

    @Test
    void rag10_futureEffectivePolicyIsFiltered() {
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                request("future card fraud draft routing",
                        "all card-not-present cases"));
        // POL-CNP-099 scores but its effectiveDate is 2027 — filtered.
        assertThat(outcome.passages())
                .noneMatch(p -> "POL-CNP-099".equals(p.documentId()));
    }

    @Test
    void rag11_withdrawnPolicyIsFiltered() {
        PolicyRetrievalOutcome outcome = retriever.retrievePolicyEvidence(
                request("legacy disputes closed automatically thirty days",
                        "withdrawn legacy circular"));
        assertThat(outcome.passages())
                .noneMatch(p -> "POL-LEG-050".equals(p.documentId()));
    }

    @Test
    void rag12_citationMapsTrustedRetrievalMetadataVerbatim() {
        PolicyRetrievalOutcome outcome =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        PolicyPassage p = outcome.passages().get(0);
        PolicyCitation citation = PolicyCitation.from(p);
        assertThat(citation.documentId()).isEqualTo(p.documentId());
        assertThat(citation.version()).isEqualTo(p.version());
        assertThat(citation.section()).isEqualTo(p.section());
        assertThat(citation.chunkId()).isEqualTo(p.chunkId());
        assertThat(citation.score()).isEqualTo(p.score());
        assertThat(citation.title()).isEqualTo(p.title());
        assertThat(citation.effectiveDate())
                .isEqualTo(p.effectiveDate().toString());
        assertThat(citation.passage()).isEqualTo(p.passage());
    }

    @Test
    void rag13_passageEqualsIngestedChunkExactly() {
        PolicyRetrievalOutcome outcome =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        for (PolicyPassage p : outcome.passages()) {
            PolicyChunk chunk = catalog.chunk(p.chunkId()).orElseThrow();
            assertThat(p.passage()).isEqualTo(chunk.text());
        }
    }

    @Test
    void rag29_vectorStoreFailureYieldsUnavailableNotException() {
        VectorStore failing = new VectorStore() {
            @Override
            public void add(List<Document> documents) {
            }

            @Override
            public void delete(List<String> idList) {
            }

            @Override
            public void delete(Filter.Expression filterExpression) {
            }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                throw new IllegalStateException("synthetic store outage");
            }
        };
        PolicyRetriever broken = new PolicyRetriever(failing, catalog,
                4, THRESHOLD, Clock.systemDefaultZone());
        PolicyRetrievalOutcome outcome =
                broken.retrievePolicyEvidence(standardCnpRequest());
        assertThat(outcome.status())
                .isEqualTo(PolicyRetrievalOutcome.Status.UNAVAILABLE);
        assertThat(outcome.failureReason()).contains("IllegalStateException");
    }

    @Test
    void retrievalIsDeterministicAcrossRuns() {
        PolicyRetrievalOutcome a =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        PolicyRetrievalOutcome b =
                retriever.retrievePolicyEvidence(standardCnpRequest());
        assertThat(a.passages().stream().map(PolicyPassage::chunkId).toList())
                .isEqualTo(b.passages().stream().map(PolicyPassage::chunkId).toList());
    }
}
