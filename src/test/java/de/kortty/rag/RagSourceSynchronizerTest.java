package de.kortty.rag;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class RagSourceSynchronizerTest {
    @Test
    void treatsRenameAsDeletePlusAddWithoutLeavingStaleChunks() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-sync-rename");
        try {
            Path sourceDir = Files.createDirectories(root.resolve("source"));
            Path original = Files.writeString(sourceDir.resolve("before.md"), "same content");
            RagSource source = new RagSource("source-id", "Source", sourceDir, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(3);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 3, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                new RagSourceScanner(), new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);

            Files.move(original, sourceDir.resolve("after.md"));
            RagSyncResult renamed = synchronizer.synchronize(source, CancellationToken.NONE);

            assertThat(renamed.removedDocuments()).isEqualTo(1);
            assertThat(renamed.embeddedDocuments()).isEqualTo(1);
            assertThat(store.chunksForSource(source.id()).stream()
                .map(value -> value.chunk().documentPath()).distinct().toList())
                .containsExactly("after.md");
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void reusesUnchangedHashesAndOnlyEmbedsChangedDocuments() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-sync");
        try {
            Path sourceDir = Files.createDirectories(root.resolve("source"));
            Path first = Files.writeString(sourceDir.resolve("first.md"), "first version");
            Path second = Files.writeString(sourceDir.resolve("second.md"), "second version");
            RagSource source = new RagSource("source-id", "Source", sourceDir, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(4);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 4, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                new RagSourceScanner(), new RagChunker(), embeddings, store);

            RagSyncResult initial = synchronizer.synchronize(source, CancellationToken.NONE);
            int initialEmbeddings = embeddings.count();
            RagSyncResult unchanged = synchronizer.synchronize(source, CancellationToken.NONE);
            Files.writeString(first, "first changed");
            RagSyncResult changed = synchronizer.synchronize(source, CancellationToken.NONE);
            Files.delete(second);
            RagSyncResult removed = synchronizer.synchronize(source, CancellationToken.NONE);

            assertThat(initial.embeddedDocuments()).isEqualTo(2);
            assertThat(unchanged.reusedDocuments()).isEqualTo(2);
            assertThat(embeddings.count()).isEqualTo(initialEmbeddings + 1);
            assertThat(changed.embeddedDocuments()).isEqualTo(1);
            assertThat(removed.removedDocuments()).isEqualTo(1);
            assertThat(store.chunksForSource(source.id()).stream()
                .map(chunk -> chunk.chunk().documentPath()).distinct().toList()).containsExactly("first.md");
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void deletedSingleFileSourceRemovesItsPreviouslyCommittedChunks() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-sync-missing");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "ready");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(3);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 3, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                new RagSourceScanner(), new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);
            Files.delete(file);

            RagSyncResult missing = synchronizer.synchronize(source, CancellationToken.NONE);

            assertThat(missing.status()).isEqualTo(RagSourceStatus.MISSING);
            assertThat(missing.documents()).isEqualTo(0);
            assertThat(missing.chunks()).isEqualTo(0);
            assertThat(missing.removedDocuments()).isEqualTo(1);
            assertThat(store.chunksForSource(source.id())).isEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void disabledSourceDoesNotMutateItsExistingIndex() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-sync-disabled");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "ready");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(2);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 2, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                new RagSourceScanner(), new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);

            RagSyncResult result = synchronizer.synchronize(source.withEnabled(false), CancellationToken.NONE);

            assertThat(result.status()).isEqualTo(RagSourceStatus.DISABLED);
            assertThat(store.chunksForSource(source.id())).hasSize(1);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void cancellationDuringEmbeddingKeepsLastGoodSourceSnapshot() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-sync-cancel");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "old content");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            CancellationToken.Source cancellation = CancellationToken.source();
            final boolean[] cancelOnEmbed = {false};
            EmbeddingService embeddings = new EmbeddingService() {
                @Override public String modelId() { return "cancel-model"; }
                @Override public int dimensions() { return 2; }
                @Override public List<float[]> embed(List<String> texts, CancellationToken token) {
                    if (cancelOnEmbed[0]) {
                        cancellation.cancel();
                        token.throwIfCancelled();
                    }
                    return texts.stream().map(ignored -> new float[] {1, 1}).toList();
                }
            };
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 2, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                new RagSourceScanner(), new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);
            String oldHash = store.chunksForSource(source.id()).get(0).chunk().documentHash();
            Files.writeString(file, "new content");
            cancelOnEmbed[0] = true;

            try {
                synchronizer.synchronize(source, cancellation.token());
                throw new AssertionError("expected cancellation");
            } catch (java.util.concurrent.CancellationException expected) {
                // expected
            }
            assertThat(store.chunksForSource(source.id()).get(0).chunk().documentHash()).isEqualTo(oldHash);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedPreviewRejectsAFileChangedBeforeIndexing() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-confirmed-stale-before");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "approved content");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagSourceScanner scanner = new RagSourceScanner();
            RagScanPreview confirmed = scanner.preview(source, CancellationToken.NONE);
            Files.writeString(file, "unreviewed content");

            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(3);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 3, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                scanner, new RagChunker(), embeddings, store);

            try {
                synchronizer.synchronizeConfirmed(confirmed, CancellationToken.NONE);
                throw new AssertionError("expected a stale-preview failure");
            } catch (RagSourceSynchronizer.ConfirmedPreviewStaleException expected) {
                assertThat(expected).hasMessageThat().contains("review and confirm");
            }
            assertThat(embeddings.count()).isEqualTo(0);
            assertThat(store.chunksForSource(source.id())).isEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedPreviewIndexesTheReviewedSnapshotWhenItRemainsCurrent() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-confirmed-current");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "reviewed content");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagSourceScanner scanner = new RagSourceScanner();
            RagScanPreview confirmed = scanner.preview(source, CancellationToken.NONE);
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(3);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 3, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                scanner, new RagChunker(), embeddings, store);

            RagSyncResult result = synchronizer.synchronizeConfirmed(confirmed, CancellationToken.NONE);

            assertThat(result.status()).isEqualTo(RagSourceStatus.READY);
            assertThat(result.documentHashes()).containsExactly(
                file.getFileName().toString(), confirmed.documents().getFirst().sha256());
            assertThat(store.chunksForSource(source.id())).isNotEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedDirectoryPreviewRejectsNewPathsAddedAfterReview() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-confirmed-added-path");
        try {
            Path sourceDir = Files.createDirectories(root.resolve("source"));
            Files.writeString(sourceDir.resolve("reviewed.md"), "reviewed");
            RagSource source = new RagSource("source-id", "Source", sourceDir, RagSourceType.DIRECTORY,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagSourceScanner scanner = new RagSourceScanner();
            RagScanPreview confirmed = scanner.preview(source, CancellationToken.NONE);
            Files.writeString(sourceDir.resolve("not-reviewed.md"), "new");

            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(2);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 2, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                scanner, new RagChunker(), embeddings, store);

            try {
                synchronizer.synchronizeConfirmed(confirmed, CancellationToken.NONE);
                throw new AssertionError("expected a stale-preview failure");
            } catch (RagSourceSynchronizer.ConfirmedPreviewStaleException expected) {
                // expected
            }
            assertThat(store.chunksForSource(source.id())).isEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedPreviewRechecksFilesAfterEmbeddingBeforeAtomicCommit() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-confirmed-stale-during");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "old committed content");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagSourceScanner scanner = new RagSourceScanner();
            final boolean[] mutateDuringEmbedding = {false};
            EmbeddingService embeddings = new EmbeddingService() {
                @Override public String modelId() { return "mutating-embedding"; }
                @Override public int dimensions() { return 2; }

                @Override
                public List<float[]> embed(List<String> texts, CancellationToken cancellation) throws Exception {
                    if (mutateDuringEmbedding[0]) {
                        Files.writeString(file, "changed while embedding");
                    }
                    return texts.stream().map(ignored -> new float[] {1, 1}).toList();
                }
            };
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 2, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                scanner, new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);
            String oldHash = store.chunksForSource(source.id()).getFirst().chunk().documentHash();

            Files.writeString(file, "approved replacement");
            RagScanPreview confirmed = scanner.preview(source, CancellationToken.NONE);
            mutateDuringEmbedding[0] = true;

            try {
                synchronizer.synchronizeConfirmed(confirmed, CancellationToken.NONE);
                throw new AssertionError("expected a stale-preview failure");
            } catch (RagSourceSynchronizer.ConfirmedPreviewStaleException expected) {
                // expected
            }
            assertThat(store.chunksForSource(source.id()).getFirst().chunk().documentHash())
                .isEqualTo(oldHash);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedPreviewGuardRechecksPersistedSettingsBeforeCommit() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-confirmed-config-guard");
        try {
            Path file = Files.writeString(root.resolve("source.md"), "old committed content");
            RagSource source = new RagSource("source-id", "Source", file, RagSourceType.FILE,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            RagSourceScanner scanner = new RagSourceScanner();
            RagTestSupport.CountingEmbedding embeddings = new RagTestSupport.CountingEmbedding(2);
            LocalHnswStore store = new LocalHnswStore(root.resolve("index"), 2, embeddings.modelId());
            RagSourceSynchronizer synchronizer = new RagSourceSynchronizer(
                scanner, new RagChunker(), embeddings, store);
            synchronizer.synchronize(source, CancellationToken.NONE);
            String oldHash = store.chunksForSource(source.id()).getFirst().chunk().documentHash();

            Files.writeString(file, "approved replacement");
            RagScanPreview confirmed = scanner.preview(source, CancellationToken.NONE);
            int[] checks = {0};
            try {
                synchronizer.synchronizeConfirmed(confirmed, CancellationToken.NONE, () -> {
                    if (++checks[0] > 1) {
                        throw new RagSourceSynchronizer.ConfirmedPreviewStaleException(
                            "settings changed");
                    }
                });
                throw new AssertionError("expected the final configuration guard to fail");
            } catch (RagSourceSynchronizer.ConfirmedPreviewStaleException expected) {
                // expected
            }

            assertThat(checks[0]).isEqualTo(2);
            assertThat(store.chunksForSource(source.id()).getFirst().chunk().documentHash())
                .isEqualTo(oldHash);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }
}
