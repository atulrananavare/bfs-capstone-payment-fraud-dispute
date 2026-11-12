package com.example.fraudcase.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads synthetic policy resources, validates metadata, chunks by section
 * and indexes through the Spring AI {@link VectorStore} while registering
 * trusted metadata in the {@link PolicyCatalog}.
 *
 * <p>Idempotent: chunk ids are deterministic, so re-ingesting the same
 * source overwrites rather than duplicates. A malformed document is
 * rejected and reported — it never aborts the rest of the batch or leaks
 * partial state into the catalog.
 */
public class PolicyIngestionService {

    private static final Logger log =
            LoggerFactory.getLogger(PolicyIngestionService.class);

    public record IngestionResult(int filesProcessed,
                                  int chunksIndexed,
                                  int chunksSkipped,
                                  List<String> rejections) {

        public IngestionResult {
            rejections = List.copyOf(rejections);
        }

        public boolean clean() {
            return rejections.isEmpty();
        }
    }

    private final VectorStore vectorStore;
    private final PolicyCatalog catalog;

    public PolicyIngestionService(VectorStore vectorStore,
                                  PolicyCatalog catalog) {
        this.vectorStore = vectorStore;
        this.catalog = catalog;
    }

    public IngestionResult ingest(List<? extends Resource> sources) {
        int files = 0;
        int indexed = 0;
        int skipped = 0;
        List<String> rejections = new ArrayList<>();
        for (Resource source : sources) {
            files++;
            String name = source.getFilename() == null
                    ? "<stream>" : source.getFilename();
            try {
                IngestionResult single = ingestText(name,
                        source.getContentAsString(StandardCharsets.UTF_8));
                indexed += single.chunksIndexed();
                skipped += single.chunksSkipped();
                rejections.addAll(single.rejections());
            } catch (IOException ex) {
                rejections.add(name + ": unreadable resource");
                log.warn("POLICY_INGEST_REJECTED file={} reason=unreadable", name);
            }
        }
        IngestionResult result =
                new IngestionResult(files, indexed, skipped, rejections);
        log.info("POLICY_INGESTION files={} indexed={} skipped={} rejected={}",
                result.filesProcessed(), result.chunksIndexed(),
                result.chunksSkipped(), result.rejections().size());
        return result;
    }

    /** Ingest a single policy document body (testable without resources). */
    public IngestionResult ingestText(String sourceName, String content) {
        List<String> rejections = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        PolicyDocumentParser.ParsedPolicy parsed;
        try {
            parsed = PolicyDocumentParser.parse(content);
        } catch (IllegalArgumentException ex) {
            rejections.add(sourceName + ": " + ex.getMessage());
            log.warn("POLICY_INGEST_REJECTED file={} reason=parse", sourceName);
            return new IngestionResult(1, 0, 0, rejections);
        }
        List<String> violations =
                PolicyMetadata.violations(parsed.frontMatter());
        if (!violations.isEmpty()) {
            rejections.add(sourceName + ": " + String.join("; ", violations));
            log.warn("POLICY_INGEST_REJECTED file={} reason=metadata",
                    sourceName);
            return new IngestionResult(1, 0, 0, rejections);
        }
        PolicyMetadata metadata = PolicyMetadata.from(parsed.frontMatter());
        List<Document> documents = new ArrayList<>();
        List<PolicyChunk> newChunks = new ArrayList<>();
        for (PolicyChunk chunk
                : PolicyChunker.chunk(metadata, parsed.sections())) {
            if (catalog.contains(chunk.chunkId())) {
                skipped++;
                continue;
            }
            newChunks.add(chunk);
            documents.add(new Document(chunk.chunkId(), chunk.text(),
                    toStoreMetadata(chunk)));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);   // embeds each chunk
            newChunks.forEach(catalog::register);
            indexed += newChunks.size();
        }
        return new IngestionResult(1, indexed, skipped, rejections);
    }

    static Map<String, Object> toStoreMetadata(PolicyChunk chunk) {
        PolicyMetadata m = chunk.metadata();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentId", m.documentId());
        meta.put("title", m.title());
        meta.put("version", m.version());
        meta.put("effectiveDate", m.effectiveDate().toString());
        meta.put("owner", m.owner());
        meta.put("classification", m.classification());
        meta.put("status", m.status().name());
        meta.put("section", chunk.section());
        meta.put("sectionIndex", chunk.sectionIndex());
        meta.put("chunkIndex", chunk.chunkIndex());
        return meta;
    }
}
