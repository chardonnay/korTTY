package de.kortty.rag;

import org.testng.annotations.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class QdrantVectorStoreTest {
    @Test
    void facadeInitializesAndDelegatesDomainOperations() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        QdrantVectorStore store = new QdrantVectorStore("knowledge", 2, "model", adapter);
        RagEmbeddedChunk value = RagTestSupport.embedded("id", "source", "doc.md", 1, 0);

        store.initialize();
        store.replaceSource("source", List.of(value), CancellationToken.NONE);
        List<RagSearchResult> hits = store.search(new float[] {1, 0}, 6, Set.of("source"), CancellationToken.NONE);
        store.removeSource("source", CancellationToken.NONE);

        assertThat(adapter.initialized).isTrue();
        assertThat(adapter.values).isEmpty();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunk().id()).isEqualTo("id");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsVectorDimensionMismatchBeforeRemoteMutation() throws Exception {
        QdrantVectorStore store = new QdrantVectorStore("knowledge", 3, "model", new FakeAdapter());
        store.replaceSource("source", List.of(
            RagTestSupport.embedded("id", "source", "doc.md", 1, 0)), CancellationToken.NONE);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void httpAdapterRejectsNonHttpEndpoint() {
        new HttpQdrantRestAdapter(URI.create("file:///tmp/qdrant"), "");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void httpAdapterRejectsPlainHttpForRemoteEndpoints() {
        new HttpQdrantRestAdapter(URI.create("http://qdrant.example.test:6333"), "secret");
    }

    @Test
    void httpAdapterAllowsPlainHttpForLocalQdrant() {
        new HttpQdrantRestAdapter(URI.create("http://127.0.0.1:6333"), "");
    }

    private static final class FakeAdapter implements QdrantRestAdapter {
        private boolean initialized;
        private final List<RagEmbeddedChunk> values = new ArrayList<>();

        @Override public void ensureCollection(String collectionName, int dimensions) { initialized = true; }
        @Override public List<RagEmbeddedChunk> readSource(String collectionName, String sourceId) {
            return values.stream().filter(value -> value.chunk().sourceId().equals(sourceId)).toList();
        }
        @Override public void replaceSource(String collectionName, String sourceId,
                                            Collection<RagEmbeddedChunk> chunks, CancellationToken cancellation) {
            values.removeIf(value -> value.chunk().sourceId().equals(sourceId));
            values.addAll(chunks);
        }
        @Override public void removeSource(String collectionName, String sourceId, CancellationToken cancellation) {
            values.removeIf(value -> value.chunk().sourceId().equals(sourceId));
        }
        @Override public List<RagSearchResult> search(String collectionName, float[] queryVector, int limit,
                                                     Set<String> sourceIds, CancellationToken cancellation) {
            return values.stream().filter(value -> sourceIds.isEmpty()
                    || sourceIds.contains(value.chunk().sourceId()))
                .limit(limit).map(value -> new RagSearchResult(value.chunk(), 1, value.chunk().citation())).toList();
        }
    }
}
