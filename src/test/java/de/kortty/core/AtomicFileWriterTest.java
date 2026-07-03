package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Backs the "Overwrite local file" flows in MainWindow's local-shell "Load as text file" editor
 * and SFTPManagerTab's local snippet editor. Both used to truncate the target file in place via
 * TRUNCATE_EXISTING, so a failure partway through the write left the file corrupted with no
 * recovery; AtomicFileWriter must only ever replace the original via a move.
 */
class AtomicFileWriterTest {

    @Test
    void replacesFileContentCompletelyOnSuccess() throws Exception {
        Path dir = Files.createTempDirectory("kortty-atomic-write");
        try {
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, "original content");

            AtomicFileWriter.writeStringAtomically(file, "replacement content");

            assertThat(Files.readString(file)).isEqualTo("replacement content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void leavesOriginalFileIntactWhenWriteFails() throws Exception {
        Path dir = Files.createTempDirectory("kortty-atomic-write-failure");
        try {
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, "original content");

            expectThrows(NullPointerException.class, () -> AtomicFileWriter.writeStringAtomically(file, null));

            assertThat(Files.readString(file)).isEqualTo("original content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void preservesPosixPermissionsAcrossOverwrite() throws Exception {
        Path dir = Files.createTempDirectory("kortty-atomic-write-permissions");
        try {
            Path file = dir.resolve("script.sh");
            Files.writeString(file, "original content");
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view == null) {
                throw new SkipException("POSIX file attributes are not supported on this platform");
            }
            Set<PosixFilePermission> worldReadable = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
            view.setPermissions(worldReadable);

            AtomicFileWriter.writeStringAtomically(file, "replacement content");

            assertThat(Files.getPosixFilePermissions(file)).containsExactlyElementsIn(worldReadable);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void writesThroughSymlinkWithoutReplacingIt() throws Exception {
        Path dir = Files.createTempDirectory("kortty-atomic-write-symlink");
        try {
            Path realFile = dir.resolve("real-notes.txt");
            Files.writeString(realFile, "original content");
            Path symlink = dir.resolve("notes.txt");
            try {
                Files.createSymbolicLink(symlink, realFile);
            } catch (UnsupportedOperationException | IOException e) {
                throw new SkipException("Symbolic links are not supported on this platform");
            }

            AtomicFileWriter.writeStringAtomically(symlink, "replacement content");

            assertThat(Files.isSymbolicLink(symlink)).isTrue();
            assertThat(Files.readSymbolicLink(symlink)).isEqualTo(realFile);
            assertThat(Files.readString(realFile)).isEqualTo("replacement content");
            assertThat(Files.readString(symlink)).isEqualTo("replacement content");
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
