package de.kortty.rag;

import de.kortty.ai.llama.EmbeddedLlamaEmbeddingService;
import de.kortty.model.AiWorkload;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Opens configured stores on demand and builds one bounded prompt context across them. */
public final class RagRuntimeService {

    private final Path configurationFile;

    public RagRuntimeService() {
        this(RagConfigurationManager.DEFAULT_FILE);
    }

    public RagRuntimeService(Path configurationFile) {
        this.configurationFile = configurationFile.toAbsolutePath().normalize();
    }

    public List<String> configuredStoreIds() {
        try {
            return new RagConfigurationManager(configurationFile).listStores().stream()
                .map(RagStore::id).toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    public RagContextBuilder.RagContext retrieve(
        List<String> storeIds,
        String query,
        int modelContextTokens,
        CancellationToken cancellation) throws Exception {

        return retrieve(storeIds, query, modelContextTokens, null, false, cancellation);
    }

    public RagContextBuilder.RagContext retrieve(
        List<String> storeIds,
        String query,
        int modelContextTokens,
        AiWorkload workload,
        boolean autonomousOnly,
        CancellationToken cancellation) throws Exception {

        if (storeIds == null || storeIds.isEmpty() || query == null || query.isBlank()) {
            return new RagContextBuilder.RagContext("", List.of(), 0, false);
        }
        RagConfigurationManager configuration = new RagConfigurationManager(configurationFile);
        List<RagSearchResult> hits = new ArrayList<>();
        for (String storeId : storeIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
            cancellation.throwIfCancelled();
            RagStore store = configuration.findStore(storeId)
                .orElseThrow(() -> new IOException("Configured knowledge store no longer exists: " + storeId));
            if (autonomousOnly && !store.autonomousEnabled()) {
                continue;
            }
            if (!autonomousOnly && workload == AiWorkload.TEXT && !store.textEnabled()) {
                continue;
            }
            if (!autonomousOnly && workload == AiWorkload.CODING && !store.codingEnabled()) {
                continue;
            }
            requireEmbeddingConfiguration(store);
            EmbeddingService embeddings = new EmbeddedLlamaEmbeddingService(
                store.embeddingModelId(), store.embeddingDimensions());
            RagVectorStore vectors = openVectorStore(store);
            Set<String> activeSources = configuration.getSources(store.id()).stream()
                .filter(RagSource::enabled)
                .map(RagSource::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            hits.addAll(new RagRetriever(embeddings, vectors).retrieve(query, activeSources, cancellation));
        }
        hits.sort(Comparator.comparingDouble(RagSearchResult::score).reversed()
            .thenComparing(result -> result.chunk().id()));
        return new RagContextBuilder().build(hits, modelContextTokens);
    }

    public RagVectorStore openVectorStore(RagStore store) throws Exception {
        requireEmbeddingConfiguration(store);
        if (store.type() == RagStoreType.LOCAL_HNSW) {
            return new LocalHnswStore(store.localDirectory(), store.embeddingDimensions(), store.embeddingModelId());
        }
        QdrantVectorStore qdrant = new QdrantVectorStore(
            store.collectionName(),
            store.embeddingDimensions(),
            store.embeddingModelId(),
            new HttpQdrantRestAdapter(store.endpoint(), RagSecretSupport.reveal(store.apiKey())));
        qdrant.initialize();
        return qdrant;
    }

    public RagSourceSynchronizer synchronizer(RagStore store) throws Exception {
        requireEmbeddingConfiguration(store);
        EmbeddingService embeddings = new EmbeddedLlamaEmbeddingService(
            store.embeddingModelId(), store.embeddingDimensions());
        return new RagSourceSynchronizer(
            new RagSourceScanner(), new RagChunker(), embeddings, openVectorStore(store));
    }

    private static void requireEmbeddingConfiguration(RagStore store) {
        if (store.embeddingModelId().isBlank() || store.embeddingDimensions() <= 0) {
            throw new IllegalStateException("Knowledge store has no embedding model configured: " + store.displayName());
        }
    }
}
