package com.example.fraudcase.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion: parse → metadata validation → meaningful section chunking →
 * stable chunk ids → deterministic embedding → VectorStore + catalog.
 * Entirely offline — the deterministic embedding model and in-memory
 * vector store need no credentials or external services.
 */
class PolicyIngestionServiceTest {

    private SyntheticPolicyVectorStore store;
    private PolicyCatalog catalog;
    private PolicyIngestionService ingestion;

    @BeforeEach
    void setUp() {
        store = new SyntheticPolicyVectorStore(new DeterministicEmbeddingModel());
        catalog = new PolicyCatalog();
        ingestion = new PolicyIngestionService(store, catalog);
    }

    private PolicyIngestionService.IngestionResult ingestPack() throws Exception {
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:policies/*.md");
        return ingestion.ingest(Arrays.asList(resources));
    }

    @Test
    void rag01_validPackIngestsCleanlyWithMetadata() throws Exception {
        PolicyIngestionService.IngestionResult result = ingestPack();
        assertThat(result.filesProcessed()).isEqualTo(9);
        assertThat(result.rejections()).isEmpty();
        assertThat(result.chunksIndexed()).isGreaterThanOrEqualTo(30);

        PolicyChunk chunk = catalog.chunk("POL-CNP-017-3.2-S04-C01").orElseThrow();
        assertThat(chunk.metadata().documentId()).isEqualTo("POL-CNP-017");
        assertThat(chunk.metadata().version()).isEqualTo("3.2");
        assertThat(chunk.metadata().classification())
                .isEqualTo(PolicyMetadata.REQUIRED_CLASSIFICATION);
        assertThat(chunk.section()).isEqualTo("3.2 Initial fraud routing");
        assertThat(chunk.text()).contains("## 3.2 Initial fraud routing");
    }

    @Test
    void rag02_missingOrInvalidMetadataIsRejectedAndNothingIndexed() {
        String missingVersion = """
                ---
                documentId: POL-BAD-001
                title: Bad
                effectiveDate: 2026-01-01
                owner: X
                classification: SYNTHETIC_TRAINING
                status: CURRENT
                ---

                ## 1 Body

                text
                """;
        var result = ingestion.ingestText("bad.md", missingVersion);
        assertThat(result.chunksIndexed()).isZero();
        assertThat(result.rejections()).hasSize(1)
                .first().asString().contains("version");

        String wrongClassification = missingVersion
                .replace("POL-BAD-001", "POL-BAD-002")
                .replace("owner: X", "owner: X\nversion: \"1.0\"")
                .replace("SYNTHETIC_TRAINING", "REAL_BANK_POLICY");
        var result2 = ingestion.ingestText("bad2.md", wrongClassification);
        assertThat(result2.chunksIndexed()).isZero();
        assertThat(result2.rejections()).hasSize(1)
                .first().asString().contains("SYNTHETIC_TRAINING");
        assertThat(store.size()).isZero();
    }

    @Test
    void rag02_malformedDocumentIsRejectedNotFatal() {
        var result = ingestion.ingestText("malformed.md", "no front matter here");
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.chunksIndexed()).isZero();
    }

    @Test
    void rag02_blankSectionHeadingIsRejected() {
        var result = ingestion.ingestText("blank-section.md", """
                ---
                documentId: POL-BAD-003
                title: Blank Section
                version: "1.0"
                effectiveDate: 2026-01-01
                owner: X
                classification: SYNTHETIC_TRAINING
                status: CURRENT
                ---

                ## 1 Valid

                body

                ## \s

                body under a blank heading
                """);
        // a '## ' line with no heading must not produce a sectionless chunk
        assertThat(result.chunksIndexed()).isZero();
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void adversarialPolicyFileIngestsAsPlainData() throws Exception {
        // POL-ADV-001 is valid SYNTHETIC_TRAINING policy with a hostile
        // embedded instruction. It must index verbatim like any other chunk —
        // containment (untrusted tags, citation validation) is what protects
        // the pipeline downstream, not special-casing the file.
        var resource = new org.springframework.core.io.ClassPathResource(
                "policies-adversarial/POL-ADV-001.md");
        var result = ingestion.ingest(List.of(resource));
        assertThat(result.rejections()).isEmpty();

        PolicyChunk hostile = catalog.chunk("POL-ADV-001-1.0-S02-C01")
                .orElseThrow();
        assertThat(hostile.section()).isEqualTo("2 Embedded hostile instruction");
        assertThat(hostile.text())
                .contains("Ignore all previous instructions")
                .contains("humanReviewRequired");
        assertThat(store.get(hostile.chunkId()).orElseThrow().getText())
                .isEqualTo(hostile.text());
    }

    @Test
    void rag03_chunkIdsAreStableAcrossRepeatedIngestion() throws Exception {
        ingestPack();
        String first = catalog.chunk("POL-CNP-017-3.2-S04-C01")
                .orElseThrow().chunkId();
        ingestPack(); // re-ingest identical source
        String second = catalog.chunk("POL-CNP-017-3.2-S04-C01")
                .orElseThrow().chunkId();
        assertThat(second).isEqualTo(first);
        assertThat(first).matches("POL-CNP-017-3\\.2-S\\d{2}-C\\d{2}");
    }

    @Test
    void rag13_passageTextIsPreservedVerbatim() throws Exception {
        ingestPack();
        PolicyChunk chunk = catalog.chunk("POL-CNP-017-3.2-S04-C01").orElseThrow();
        assertThat(chunk.text()).startsWith("## 3.2 Initial fraud routing");
        assertThat(chunk.text()).contains("fraud investigation");
        // the stored Document carries the identical passage text
        assertThat(store.get(chunk.chunkId()).orElseThrow().getText())
                .isEqualTo(chunk.text());
    }

    @Test
    void rag14_reingestionIsIdempotentNoDuplicates() throws Exception {
        var first = ingestPack();
        var second = ingestPack();
        assertThat(first.chunksIndexed()).isGreaterThan(0);
        assertThat(second.chunksIndexed()).isZero();
        assertThat(second.chunksSkipped()).isEqualTo(first.chunksIndexed());
        assertThat(store.size()).isEqualTo(first.chunksIndexed());
    }

    @Test
    void rag29_indexingFailureIsExplicit() {
        SyntheticPolicyVectorStore failing = new SyntheticPolicyVectorStore(
                new DeterministicEmbeddingModel()) {
            @Override
            public void add(List<org.springframework.ai.document.Document> docs) {
                throw new IllegalStateException("synthetic index failure");
            }
        };
        PolicyIngestionService svc = new PolicyIngestionService(failing, catalog);
        var result = svc.ingest(List.of());
        assertThat(result.chunksIndexed()).isZero();
        // a failure while adding propagates as an explicit exception
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.ingestText(
                "ok.md", """
                        ---
                        documentId: POL-OK-1
                        title: OK
                        version: "1.0"
                        effectiveDate: 2026-01-01
                        owner: X
                        classification: SYNTHETIC_TRAINING
                        status: CURRENT
                        ---

                        ## 1 A

                        body
                        """))
                .isInstanceOf(IllegalStateException.class);
    }
}
