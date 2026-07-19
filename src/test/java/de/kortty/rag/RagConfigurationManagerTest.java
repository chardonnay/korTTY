package de.kortty.rag;

import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

public class RagConfigurationManagerTest {
    @Test
    void persistsStoreAndSourcesAcrossReloadAndSupportsCrud() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config");
        try {
            Path file = root.resolve("stores.json");
            RagConfigurationManager manager = new RagConfigurationManager(file);
            RagStore store = manager.create(new RagStore("store", "Local", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "kortty", ""));
            RagSource source = new RagSource("source", "Docs", root.resolve("docs"),
                RagSourceType.DIRECTORY, RagSyncMode.AUTOMATIC, true,
                List.of("**/*.md"), List.of("private/**"));
            manager.setSources(store.id(), List.of(source));
            manager.update(new RagStore("store", "Renamed", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "kortty", ""));

            RagConfigurationManager reloaded = new RagConfigurationManager(file);
            assertThat(reloaded.listStores()).hasSize(1);
            assertThat(reloaded.findStore("store").orElseThrow().displayName()).isEqualTo("Renamed");
            assertThat(reloaded.getSources("store")).containsExactly(source);
            assertThat(reloaded.delete("store")).isTrue();
            assertThat(new RagConfigurationManager(file).listStores()).isEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void persistsQdrantConnectionFields() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config-qdrant");
        try {
            Path file = root.resolve("stores.json");
            RagStore store = new RagStore("q", "Remote", RagStoreType.QDRANT, null,
                URI.create("https://qdrant.example"), "knowledge", "secret");
            new RagConfigurationManager(file).create(store);
            assertThat(new RagConfigurationManager(file).findStore("q")).hasValue(store);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test(expectedExceptions = java.io.IOException.class)
    void rejectsMalformedRegistryWithoutReplacingIt() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config-invalid");
        try {
            Files.writeString(root.resolve("stores.json"), "{not-json");
            new RagConfigurationManager(root.resolve("stores.json"));
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsOverlappingDirectoryAndFileSources() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config-overlap");
        try {
            Path file = root.resolve("stores.json");
            RagConfigurationManager manager = new RagConfigurationManager(file);
            RagStore store = manager.create(new RagStore("store", "Local", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "kortty", ""));
            Path directory = Files.createDirectories(root.resolve("docs"));
            Path document = Files.writeString(directory.resolve("one.md"), "one");
            manager.setSources(store.id(), List.of(RagSource.directory(directory), RagSource.file(document)));
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void concurrentUiEditAndAutomaticStateUpdatePreserveBothChanges() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config-race");
        try {
            Path file = root.resolve("stores.json");
            RagConfigurationManager ui = new RagConfigurationManager(file);
            RagStore store = ui.create(new RagStore("store", "Local", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "kortty", ""));
            RagSource initial = RagSource.file(Files.writeString(root.resolve("guide.md"), "guide"));
            ui.setSources(store.id(), List.of(initial));
            RagConfigurationManager coordinator = new RagConfigurationManager(file);
            RagSource staleUiDraft = ui.getSources(store.id()).getFirst().withSyncMode(RagSyncMode.MANUAL);
            RagSyncResult completed = new RagSyncResult(
                initial.id(), RagSourceStatus.READY, 1, 3, 0, 1, 0, 0,
                Instant.parse("2026-07-15T20:00:00Z"), Map.of(initial.path().toString(), "a".repeat(64)));
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var uiWrite = executor.submit(() -> {
                    start.await();
                    ui.setSources(store.id(), List.of(staleUiDraft));
                    return null;
                });
                var syncWrite = executor.submit(() -> {
                    start.await();
                    coordinator.updateSourceState(store.id(), completed);
                    return null;
                });
                start.countDown();
                uiWrite.get(10, TimeUnit.SECONDS);
                syncWrite.get(10, TimeUnit.SECONDS);
            }

            RagSource persisted = new RagConfigurationManager(file).getSources(store.id()).getFirst();
            assertThat(persisted.syncMode()).isEqualTo(RagSyncMode.MANUAL);
            assertThat(persisted.lastStatus()).isEqualTo(RagSourceStatus.READY);
            assertThat(persisted.documentHashes()).containsExactly(
                initial.path().toString(), "a".repeat(64));
            assertThat(persisted.indexedChunks()).isEqualTo(3);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void changingTheApprovedContentScopeRequiresANewConfirmedIndex() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-config-scope-change");
        try {
            Path file = root.resolve("stores.json");
            RagConfigurationManager manager = new RagConfigurationManager(file);
            RagStore store = manager.create(new RagStore("store", "Local", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "kortty", ""));
            RagSource source = RagSource.directory(Files.createDirectories(root.resolve("docs")));
            manager.setSources(store.id(), List.of(source));
            manager.updateSourceState(store.id(), new RagSyncResult(
                source.id(), RagSourceStatus.READY, 1, 2, 0, 1, 0, 0,
                Instant.parse("2026-07-15T20:00:00Z"), Map.of("guide.md", "a".repeat(64))));
            RagSource approved = manager.getSources(store.id()).getFirst();

            manager.setSources(store.id(), List.of(approved.withAdvancedOptions(
                true, true, approved.maxFileBytes(), List.of("**/*.md"), List.of("private/**"))));

            RagSource changed = manager.getSources(store.id()).getFirst();
            assertThat(changed.lastStatus()).isEqualTo(RagSourceStatus.PENDING);
            assertThat(changed.lastSuccessfulIndex()).isNull();
            assertThat(changed.documentHashes()).containsExactly("guide.md", "a".repeat(64));
            assertThat(changed.indexedChunks()).isEqualTo(2);
            assertThat(manager.updateSourceStateIfScanConfigurationMatches(
                store.id(), approved, new RagSyncResult(
                    source.id(), RagSourceStatus.READY, 2, 4, 0, 2, 0, 0,
                    Instant.parse("2026-07-15T20:05:00Z"), Map.of("other.md", "b".repeat(64)))))
                .isFalse();
            assertThat(manager.getSources(store.id()).getFirst().lastSuccessfulIndex()).isNull();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }
}
