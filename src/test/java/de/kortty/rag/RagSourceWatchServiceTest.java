package de.kortty.rag;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

public class RagSourceWatchServiceTest {
    @Test
    void automaticSourceEmitsOneDebouncedChangeCallback() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-watch");
        try {
            Path file = Files.writeString(root.resolve("guide.md"), "initial");
            RagSource source = new RagSource("source", "Source", root, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            CountDownLatch changed = new CountDownLatch(1);
            try (RagSourceWatchService watcher = new RagSourceWatchService(Duration.ofMillis(50),
                observed -> changed.countDown())) {
                watcher.watch(source);
                Files.writeString(file, "first change");
                Files.writeString(file, "second change");
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(watcher.watchedSourceIds()).containsExactly("source");
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void manualSourceIsNotRegistered() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-watch-manual");
        try (RagSourceWatchService watcher = new RagSourceWatchService(ignored -> { })) {
            RagSource manual = new RagSource("manual", "Manual", root, RagSourceType.DIRECTORY,
                RagSyncMode.MANUAL, true, List.of(), List.of());
            watcher.watch(manual);
            assertThat(watcher.watchedSourceIds()).isEmpty();
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void recursiveRegistrationPrunesHiddenExcludedAndSymlinkedSubtrees() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-watch-pruned");
        Path external = Files.createTempDirectory("kortty-rag-watch-external");
        try {
            Path visible = Files.createDirectories(root.resolve("visible/nested"));
            Path hidden = Files.createDirectories(root.resolve(".hidden/nested"));
            Path git = Files.createDirectories(root.resolve(".git/objects"));
            Path build = Files.createDirectories(root.resolve("build/generated"));
            Path customExcluded = Files.createDirectories(root.resolve("private/nested"));
            Path gitIgnored = Files.createDirectories(root.resolve("ignored/nested"));
            Files.writeString(root.resolve(".gitignore"), "ignored/**\n");
            Path symlink = root.resolve("linked");
            boolean symlinkCreated;
            try {
                Files.createSymbolicLink(symlink, external);
                symlinkCreated = true;
            } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
                symlinkCreated = false;
            }
            RagSource source = new RagSource("source", "Source", root, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of("private/**"));

            try (RagSourceWatchService watcher = new RagSourceWatchService(ignored -> { })) {
                watcher.watch(source);

                assertThat(watcher.isDirectoryWatched(root)).isTrue();
                assertThat(watcher.isDirectoryWatched(visible)).isTrue();
                assertThat(watcher.isDirectoryWatched(hidden)).isFalse();
                assertThat(watcher.isDirectoryWatched(git)).isFalse();
                assertThat(watcher.isDirectoryWatched(build)).isFalse();
                assertThat(watcher.isDirectoryWatched(customExcluded)).isFalse();
                assertThat(watcher.isDirectoryWatched(gitIgnored)).isFalse();
                if (symlinkCreated) {
                    assertThat(watcher.isDirectoryWatched(symlink)).isFalse();
                    assertThat(watcher.isDirectoryWatched(external)).isFalse();
                }
            }
        } finally {
            RagTestSupport.deleteTree(root);
            RagTestSupport.deleteTree(external);
        }
    }

    @Test
    void recursiveRegistrationStopsAtTheConfiguredSafeDirectoryCap() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-watch-cap");
        try {
            Files.createDirectories(root.resolve("one"));
            Files.createDirectories(root.resolve("two"));
            RagSource source = new RagSource("source", "Source", root, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());

            try (RagSourceWatchService watcher = new RagSourceWatchService(
                Duration.ZERO, 2, ignored -> { })) {
                try {
                    watcher.watch(source);
                    throw new AssertionError("expected the safe watch-directory limit");
                } catch (IOException expected) {
                    assertThat(expected).hasMessageThat().contains("safe limit");
                }
                assertThat(watcher.watchedDirectoryCount()).isEqualTo(2);
            }
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }
}
