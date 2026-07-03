package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MainWindowLocalTextFileOverwriteTest {

    /**
     * Regression guard for the "Load as text file" -> Snippet Editor -> "Overwrite local file" flow:
     * the write used to truncate the target file in place, so a failure partway through the write
     * left the file corrupted with no recovery. writeStringAtomically must write to a sibling temp
     * file and only ever replace the original via move.
     */
    @Test
    void replacesFileContentCompletelyOnSuccess() throws Exception {
        Path dir = Files.createTempDirectory("kortty-overwrite-local-text-file");
        try {
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, "original content");

            MainWindow.writeStringAtomically(file, "replacement content");

            assertThat(Files.readString(file)).isEqualTo("replacement content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void leavesOriginalFileIntactWhenWriteFails() throws Exception {
        Path dir = Files.createTempDirectory("kortty-overwrite-local-text-file-failure");
        try {
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, "original content");

            expectThrows(NullPointerException.class, () -> MainWindow.writeStringAtomically(file, null));

            assertThat(Files.readString(file)).isEqualTo("original content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    private static java.util.List<Path> listTempFiles(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
