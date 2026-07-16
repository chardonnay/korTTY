package de.kortty.rag;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;

/** Runs against the version-pinned Qdrant service in the llama-runtime CI workflow. */
public class QdrantVectorStoreIntegrationTest {

    @Test
    void replacesFiltersSearchesAndRemovesRealQdrantPoints() throws Exception {
        String endpoint = System.getenv("KORTTY_TEST_QDRANT_URL");
        if (endpoint == null || endpoint.isBlank()) {
            throw new SkipException("Set KORTTY_TEST_QDRANT_URL to run the Qdrant integration test");
        }
        String collection = "kortty_test_" + UUID.randomUUID().toString().replace("-", "");
        QdrantVectorStore store = new QdrantVectorStore(collection, 2, "integration-model",
            new HttpQdrantRestAdapter(URI.create(endpoint), ""));
        store.initialize();
        RagEmbeddedChunk first = RagTestSupport.embedded("first", "source-a", "first.md", 1, 0);
        RagEmbeddedChunk second = RagTestSupport.embedded("second", "source-b", "second.md", 0, 1);

        store.replaceSource("source-a", List.of(first), CancellationToken.NONE);
        store.replaceSource("source-b", List.of(second), CancellationToken.NONE);

        assertThat(store.chunksForSource("source-a")).hasSize(1);
        List<RagSearchResult> hits = store.search(
            new float[] {1, 0}, 6, Set.of("source-a"), CancellationToken.NONE);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunk().sourceId()).isEqualTo("source-a");

        List<RagSearchResult> combined = store.search(
            new float[] {1, 0}, 6, Set.of("source-a", "source-b"), CancellationToken.NONE);
        assertThat(combined.stream().map(result -> result.chunk().sourceId()).toList())
            .containsExactly("source-a", "source-b");

        RagEmbeddedChunk replacement = RagTestSupport.embedded(
            "replacement", "source-a", "replacement.md", 1, 1);
        store.replaceSource("source-a", List.of(replacement), CancellationToken.NONE);
        assertThat(store.chunksForSource("source-a").stream()
            .map(value -> value.chunk().id()).toList()).containsExactly("replacement");

        store.removeSource("source-a", CancellationToken.NONE);
        assertThat(store.chunksForSource("source-a")).isEmpty();
        assertThat(store.chunksForSource("source-b")).hasSize(1);
    }
}
