package com.example.fraudcase.config;

import com.example.fraudcase.rag.DeterministicEmbeddingModel;
import com.example.fraudcase.rag.PolicyCatalog;
import com.example.fraudcase.rag.PolicyIngestionService;
import com.example.fraudcase.rag.PolicyRetriever;
import com.example.fraudcase.rag.SyntheticPolicyVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 4 RAG wiring. Everything here exists only when
 * {@code capstone.retrieval.enabled=true}; rules mode can run with it
 * disabled and never touches an embedding model or vector store.
 *
 * <p>Defaults are fully offline: a deterministic embedding model and an
 * in-memory vector store. A real provider/vector-database bean, if ever
 * configured, takes precedence via {@link ConditionalOnMissingBean}.
 */
@Configuration
@ConditionalOnProperty(name = "capstone.retrieval.enabled",
        havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel embeddingModel() {
        return new DeterministicEmbeddingModel();
    }

    @Bean
    @ConditionalOnMissingBean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new SyntheticPolicyVectorStore(embeddingModel);
    }

    @Bean
    PolicyIngestionService policyIngestionService(VectorStore vectorStore,
                                                  PolicyCatalog catalog) {
        return new PolicyIngestionService(vectorStore, catalog);
    }

    @Bean
    PolicyRetriever policyRetriever(VectorStore vectorStore,
                                    PolicyCatalog catalog,
                                    CapstoneProperties properties) {
        return new PolicyRetriever(vectorStore, catalog,
                properties.getRetrieval().getTopK(),
                properties.getRetrieval().getSimilarityThreshold(),
                Clock.systemDefaultZone());
    }

    /**
     * Loads the bundled synthetic policy pack at startup. Rejections are
     * recorded, never fatal — a single malformed file cannot take the
     * service down, it simply cannot be cited.
     */
    @Bean
    @ConditionalOnBean(PolicyIngestionService.class)
    ApplicationRunner policyBootstrap(
            PolicyIngestionService ingestion,
            ResourcePatternResolver resources) {
        return args -> {
            List<Resource> policies = Arrays.asList(
                    resources.getResources("classpath:policies/*.md"));
            var result = ingestion.ingest(policies);
            if (!result.clean()) {
                log.warn("POLICY_INGEST_REJECTED count={} detail={}",
                        result.rejections().size(), result.rejections());
            }
        };
    }
}
