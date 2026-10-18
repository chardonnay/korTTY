package de.kortty.core.sftp;

import de.kortty.core.sftp.FolderComparison.Entry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class FolderSnapshotsTest {

    private Path root;

    @BeforeMethod
    void createTree() throws IOException {
        root = Files.createTempDirectory("kortty-snapshot");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "class App {}");
        Files.writeString(root.resolve("README.md"), "hi");
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve("node_modules/x/index.js"), "x");
        Files.setLastModifiedTime(root.resolve("README.md"), FileTime.fromMillis(1_760_000_000_000L));
        Path outside = Files.createTempDirectory("kortty-snapshot-outside");
        Files.writeString(outside.resolve("secret.txt"), "s");
        Files.createSymbolicLink(root.resolve("linked"), outside);
    }

    @AfterMethod(alwaysRun = true)
    void deleteTree() throws IOException {
        Path outside = Files.readSymbolicLink(root.resolve("linked"));
        for (Path base : List.of(root, outside)) {
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void readsEverythingBelowWithoutLinksOrExcludedFolders() throws IOException {
        List<Entry> entries = FolderSnapshots.local(root, path -> path.equals("node_modules")
            || path.startsWith("node_modules/"), () -> false, null);

        assertThat(entries.stream().map(Entry::relativePath).sorted().toList())
            .containsExactly("README.md", "src", "src/main", "src/main/App.java").inOrder();
        Entry readme = entries.stream().filter(e -> e.relativePath().equals("README.md")).findFirst().orElseThrow();
        assertThat(readme.size()).isEqualTo(2);
        assertThat(readme.modifiedMillis()).isEqualTo(1_760_000_000_000L);
        assertThat(readme.directory()).isFalse();
    }

    @Test
    void aStopIsReportedAsSuch() {
        FolderSnapshots.StoppedException stopped = (FolderSnapshots.StoppedException) org.testng.Assert.expectThrows(
            FolderSnapshots.StoppedException.class,
            () -> FolderSnapshots.local(root, path -> false, () -> true, null));
        assertThat(stopped.tooMany()).isFalse();
        assertThrows(IOException.class, () -> FolderSnapshots.local(root.resolve("missing"), p -> false, () -> false, null));
    }
}
