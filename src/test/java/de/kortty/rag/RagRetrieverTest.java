package de.kortty.rag;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class RagRetrieverTest {
    @Test
    void returnsSixAtMostAndTwoPerSourceWithCitations() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-retrieve");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 3, "query-model");
            for (int source = 1; source <= 4; source++) {
                List<RagEmbeddedChunk> values = new ArrayList<>();
                for (int chunk = 1; chunk <= 3; chunk++) {
                    values.add(RagTestSupport.embedded("s" + source + "c" + chunk, "s" + source,
                        "doc" + source + ".md", 1, 0.01f * chunk, 0));
                }
                store.replaceSource("s" + source, values, CancellationToken.NONE);
            }
            EmbeddingService queryEmbedding = new EmbeddingService() {
                @Override public String modelId() { return "query-model"; }
                @Override public int dimensions() { return 3; }
                @Override public List<float[]> embed(List<String> texts, CancellationToken cancellation) {
                    return texts.stream().map(ignored -> new float[] {1, 0, 0}).toList();
                }
            };

            List<RagSearchResult> hits = new RagRetriever(queryEmbedding, store)
                .retrieve("query", Set.of("s1", "s2", "s3", "s4"), CancellationToken.NONE);

            assertThat(hits).hasSize(6);
            for (String source : Set.of("s1", "s2", "s3", "s4")) {
                assertThat(hits.stream().filter(hit -> hit.chunk().sourceId().equals(source)).count())
                    .isAtMost(2);
            }
            assertThat(hits.stream().map(RagSearchResult::citation).allMatch(value -> value.endsWith(".md")))
                .isTrue();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void emptyActiveSourceSelectionDoesNotStartEmbedding() throws Exception {
        final int[] calls = {0};
        EmbeddingService embedding = new EmbeddingService() {
            @Override public String modelId() { return "m"; }
            @Override public int dimensions() { return 2; }
            @Override public List<float[]> embed(List<String> texts, CancellationToken cancellation) {
                calls[0]++;
                return List.of(new float[] {1, 0});
            }
        };
        Path root = Files.createTempDirectory("kortty-rag-retrieve-empty");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 2, "m");
            assertThat(new RagRetriever(embedding, store)
                .retrieve("query", Set.of(), CancellationToken.NONE)).isEmpty();
            assertThat(calls[0]).isEqualTo(0);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }
}
