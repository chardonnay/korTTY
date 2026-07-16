package de.kortty.rag;

import org.testng.annotations.Test;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static com.google.common.truth.Truth.assertThat;

public class LocalHnswStoreTest {
    @Test
    void searchesByCosineAndReloadsAtomicSnapshot() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 3, "model");
            store.replaceSource("s1", List.of(
                RagTestSupport.embedded("near", "s1", "near.md", 1, 0, 0),
                RagTestSupport.embedded("far", "s1", "far.md", 0, 1, 0)), CancellationToken.NONE);
            store.replaceSource("s2", List.of(
                RagTestSupport.embedded("middle", "s2", "middle.md", 0.8f, 0.2f, 0)), CancellationToken.NONE);

            List<RagSearchResult> result = store.search(new float[] {1, 0, 0}, 3, Set.of(), CancellationToken.NONE);
            assertThat(result.stream().map(hit -> hit.chunk().id()).toList())
                .containsExactly("near", "middle", "far").inOrder();

            LocalHnswStore reloaded = new LocalHnswStore(root, 3, "model");
            assertThat(reloaded.size()).isEqualTo(3);
            assertThat(reloaded.chunksForSource("s2")).hasSize(1);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void cancelledReplacementLeavesMemoryAndDiskSnapshotUnchanged() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw-cancel");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 2, "model");
            store.replaceSource("s", List.of(
                RagTestSupport.embedded("old", "s", "old.md", 1, 0)), CancellationToken.NONE);
            CancellationToken.Source cancellation = CancellationToken.source();
            cancellation.cancel();
            try {
                store.replaceSource("s", List.of(
                    RagTestSupport.embedded("new", "s", "new.md", 0, 1)), cancellation.token());
                throw new AssertionError("expected cancellation");
            } catch (CancellationException expected) {
                // expected
            }
            assertThat(store.chunksForSource("s").get(0).chunk().id()).isEqualTo("old");
            assertThat(new LocalHnswStore(root, 2, "model").chunksForSource("s").get(0).chunk().id())
                .isEqualTo("old");
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void separateStoreInstancesMergeAgainstLatestSnapshotInsteadOfLosingSources() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw-multi-instance");
        try {
            LocalHnswStore first = new LocalHnswStore(root, 2, "model");
            LocalHnswStore staleSecond = new LocalHnswStore(root, 2, "model");
            first.replaceSource("s1", List.of(
                RagTestSupport.embedded("one", "s1", "one.md", 1, 0)), CancellationToken.NONE);
            staleSecond.replaceSource("s2", List.of(
                RagTestSupport.embedded("two", "s2", "two.md", 0, 1)), CancellationToken.NONE);

            LocalHnswStore reloaded = new LocalHnswStore(root, 2, "model");
            assertThat(reloaded.chunksForSource("s1")).hasSize(1);
            assertThat(reloaded.chunksForSource("s2")).hasSize(1);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test(expectedExceptions = LocalHnswStore.IncompatibleIndexException.class)
    void detectsEmbeddingModelChange() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw-model");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 2, "old-model");
            store.replaceSource("s", List.of(
                RagTestSupport.embedded("id", "s", "doc.md", 1, 0)), CancellationToken.NONE);
            new LocalHnswStore(root, 2, "new-model");
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void buildsMultipleHnswLayersAndSearchesSparseActiveSourceWithoutExhaustiveScoring() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw-filtered");
        try {
            LocalHnswStore store = new LocalHnswStore(root, 8, "model", 8, 96, 32);
            List<RagEmbeddedChunk> inactive = new ArrayList<>();
            for (int index = 0; index < 1_024; index++) {
                inactive.add(RagTestSupport.embedded(
                    "inactive-" + index, "inactive", "inactive-" + index + ".md", vectorFor(index)));
            }
            List<RagEmbeddedChunk> active = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                active.add(RagTestSupport.embedded(
                    "active-" + index, "active", "active-" + index + ".md", vectorFor(2_000 + index)));
            }
            store.replaceSource("inactive", inactive, CancellationToken.NONE);
            store.replaceSource("active", active, CancellationToken.NONE);

            List<RagSearchResult> results = store.search(
                vectorFor(2_031), 8, Set.of("active"), CancellationToken.NONE);

            assertThat(store.graphLevelCount()).isGreaterThan(1);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).chunk().id()).isEqualTo("active-31");
            assertThat(results.stream().map(result -> result.chunk().sourceId()).distinct().toList())
                .containsExactly("active");
            assertThat(store.lastSearchVisitedCount()).isLessThan(store.size());
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void migratesLegacyBaseLayerSnapshotToHierarchicalVersionAtomically() throws Exception {
        Path root = Files.createTempDirectory("kortty-hnsw-v1");
        try {
            Files.createDirectories(root);
            Path snapshot = root.resolve("index.hnsw");
            RagEmbeddedChunk first = RagTestSupport.embedded("first", "source", "first.md", 1, 0);
            RagEmbeddedChunk second = RagTestSupport.embedded("second", "source", "second.md", 0, 1);
            try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(snapshot)))) {
                output.write("KORTHNS1".getBytes(StandardCharsets.US_ASCII));
                output.writeInt(1);
                output.writeInt(2);
                writeString(output, "model");
                output.writeInt(2);
                writeLegacyNode(output, first, 1);
                writeLegacyNode(output, second, 0);
            }

            LocalHnswStore store = new LocalHnswStore(root, 2, "model");
            assertThat(store.size()).isEqualTo(2);
            assertThat(store.search(new float[] {1, 0}, 1, Set.of("source"), CancellationToken.NONE)
                .get(0).chunk().id()).isEqualTo("first");

            try (DataInputStream input = new DataInputStream(Files.newInputStream(snapshot))) {
                assertThat(new String(input.readNBytes(8), StandardCharsets.US_ASCII)).isEqualTo("KORTHNS1");
                assertThat(input.readInt()).isEqualTo(2);
            }
            try (var files = Files.list(root)) {
                assertThat(files
                    .filter(path -> path.getFileName().toString().contains(".tmp-"))
                    .toList()).isEmpty();
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    private static float[] vectorFor(int seed) {
        float[] vector = new float[8];
        for (int dimension = 0; dimension < vector.length; dimension++) {
            vector[dimension] = (float) (
                Math.sin((seed + 1) * (dimension + 1) * 0.173)
                    + Math.cos((seed + 3) * (dimension + 2) * 0.097));
        }
        return vector;
    }

    private static void writeLegacyNode(
        DataOutputStream output,
        RagEmbeddedChunk embedded,
        int neighbor
    ) throws Exception {
        RagChunk chunk = embedded.chunk();
        writeString(output, chunk.id());
        writeString(output, chunk.sourceId());
        writeString(output, chunk.documentPath());
        writeString(output, chunk.documentHash());
        output.writeInt(chunk.chunkIndex());
        output.writeInt(chunk.startOffset());
        output.writeInt(chunk.endOffset());
        writeString(output, chunk.text());
        output.writeInt(chunk.metadata().size());
        for (Map.Entry<String, String> metadata : chunk.metadata().entrySet()) {
            writeString(output, metadata.getKey());
            writeString(output, metadata.getValue());
        }
        for (float component : embedded.vector()) {
            output.writeFloat(component);
        }
        output.writeInt(1);
        output.writeInt(neighbor);
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
