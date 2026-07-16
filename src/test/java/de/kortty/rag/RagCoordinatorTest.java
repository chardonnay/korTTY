package de.kortty.rag;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

public class RagCoordinatorTest {

    @Test
    void reloadsStoresCreatedAfterStartupAndAcceptsTheirSources() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-coordinator-reload");
        try {
            Path registryFile = root.resolve("rag/stores.json");
            RagConfigurationManager coordinatorConfiguration = new RagConfigurationManager(registryFile);
            try (RagCoordinator coordinator = new RagCoordinator(coordinatorConfiguration)) {
                coordinator.start();

                RagConfigurationManager uiConfiguration = new RagConfigurationManager(registryFile);
                RagStore store = uiConfiguration.create(new RagStore(
                    "new-store", "New store", RagStoreType.LOCAL_HNSW,
                    root.resolve("index"), null, "unused", "",
                    "embedding-model", 3, true, true, false));
                Path document = Files.writeString(root.resolve("guide.md"), "local knowledge");
                RagSource source = RagSource.file(document).withEnabled(false);
                uiConfiguration.setSources(store.id(), List.of(source));

                coordinator.refreshConfiguration();
                List<RagSyncResult> result = coordinator
                    .synchronize(store, List.of(source), CancellationToken.NONE)
                    .get(10, TimeUnit.SECONDS);

                assertThat(result).hasSize(1);
                assertThat(result.getFirst().status()).isEqualTo(RagSourceStatus.DISABLED);

                uiConfiguration.setSources(store.id(), List.of());
                coordinator.refreshConfiguration();
                coordinator.removeSources(store, List.of(source)).get(10, TimeUnit.SECONDS);
                assertThat(uiConfiguration.delete(store.id())).isTrue();
                coordinator.refreshConfiguration();
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void partialManualSynchronizationKeepsEveryAutomaticSourceWatched() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-coordinator-watchers");
        try {
            Path registryFile = root.resolve("rag/stores.json");
            RagConfigurationManager configuration = new RagConfigurationManager(registryFile);
            RagStore store = configuration.create(new RagStore(
                "watch-store", "Watch store", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "unused", "",
                "embedding-model", 3, true, true, false));
            Path firstDirectory = Files.createDirectories(root.resolve("first"));
            Path secondDirectory = Files.createDirectories(root.resolve("second"));
            RagSource first = approved(RagSource.directory(firstDirectory));
            RagSource second = approved(RagSource.directory(secondDirectory));
            RagSource manual = RagSource.file(Files.writeString(root.resolve("manual.md"), "manual"))
                .withSyncMode(RagSyncMode.MANUAL)
                .withEnabled(false);
            configuration.setSources(store.id(), List.of(first, second, manual));

            try (RagCoordinator coordinator = new RagCoordinator(new RagConfigurationManager(registryFile))) {
                coordinator.refreshConfiguration();
                assertThat(coordinator.watchedSourceIds(store.id()))
                    .containsExactly(first.id(), second.id());

                coordinator.synchronize(store, List.of(manual), CancellationToken.NONE)
                    .get(10, TimeUnit.SECONDS);

                assertThat(coordinator.watchedSourceIds(store.id()))
                    .containsExactly(first.id(), second.id());
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void doesNotWatchAnAutomaticSourceUntilItsFirstConfirmedIndexSucceeded() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-coordinator-unapproved-watch");
        try {
            Path registryFile = root.resolve("rag/stores.json");
            RagConfigurationManager configuration = new RagConfigurationManager(registryFile);
            RagStore store = configuration.create(new RagStore(
                "watch-store", "Watch store", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "unused", "",
                "embedding-model", 3, true, true, false));
            RagSource pending = RagSource.directory(Files.createDirectories(root.resolve("pending")));
            configuration.setSources(store.id(), List.of(pending));

            try (RagCoordinator coordinator = new RagCoordinator(new RagConfigurationManager(registryFile))) {
                coordinator.refreshConfiguration();
                assertThat(coordinator.watchedSourceIds(store.id())).isEmpty();
                try {
                    coordinator.synchronize(store, List.of(pending), CancellationToken.NONE)
                        .get(10, TimeUnit.SECONDS);
                    throw new AssertionError("expected initial automatic sync to require confirmation");
                } catch (ExecutionException expected) {
                    assertThat(expected.getCause()).hasMessageThat().contains("confirmed preview");
                }
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void confirmedSynchronizationRejectsScanSettingsChangedAfterReview() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-coordinator-confirmed-settings");
        try {
            Path registryFile = root.resolve("rag/stores.json");
            RagConfigurationManager configuration = new RagConfigurationManager(registryFile);
            RagStore store = configuration.create(new RagStore(
                "confirmed-store", "Confirmed store", RagStoreType.LOCAL_HNSW,
                root.resolve("index"), null, "unused", "",
                "embedding-model", 3, true, true, false));
            Path file = Files.writeString(root.resolve("guide.md"), "approved");
            RagSource source = RagSource.file(file).withSyncMode(RagSyncMode.MANUAL);
            configuration.setSources(store.id(), List.of(source));
            RagScanPreview preview = new RagSourceScanner().preview(source, CancellationToken.NONE);
            configuration.setSources(store.id(), List.of(source.withAdvancedOptions(
                false, true, source.maxFileBytes() - 1, List.of(), List.of())));

            try (RagCoordinator coordinator = new RagCoordinator(new RagConfigurationManager(registryFile))) {
                try {
                    coordinator.synchronizeConfirmed(store, List.of(preview), CancellationToken.NONE)
                        .get(10, TimeUnit.SECONDS);
                    throw new AssertionError("expected changed scan settings to invalidate the preview");
                } catch (ExecutionException expected) {
                    assertThat(expected.getCause()).hasMessageThat().contains("settings changed");
                }
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    private static RagSource approved(RagSource source) {
        return source.withIndexState(new RagSyncResult(
            source.id(), RagSourceStatus.READY, 0, 0, 0, 0, 0, 0,
            Instant.EPOCH, Map.of()));
    }
}
