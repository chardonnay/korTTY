package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    void storeWriteOwnerOnlyTightensAWorldReadableFile() throws Exception {
        Path dir = Files.createTempDirectory("kortty-store-write-owner-only");
        try {
            Path file = dir.resolve("connections.xml");
            Files.writeString(file, "<connections/>");
            requirePosix(file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

            AtomicFileWriter.writeStoreAtomically(file, "<connections><connection/></connections>",
                AtomicFileWriter.FileMode.OWNER_ONLY);

            assertThat(Files.readString(file)).isEqualTo("<connections><connection/></connections>");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void storeWritePreserveKeepsTheExistingMode() throws Exception {
        Path dir = Files.createTempDirectory("kortty-store-write-preserve");
        try {
            Path file = dir.resolve("themes.xml");
            Files.writeString(file, "<themes/>");
            requirePosix(file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

            AtomicFileWriter.writeStoreAtomically(file, "<themes><theme/></themes>", AtomicFileWriter.FileMode.PRESERVE);

            assertThat(Files.readString(file)).isEqualTo("<themes><theme/></themes>");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void storeWriteOfANewFileIsOwnerOnly() throws Exception {
        Path dir = Files.createTempDirectory("kortty-store-write-new");
        try {
            requirePosix(dir);
            Path preserved = dir.resolve("environments.xml");
            Path ownerOnly = dir.resolve("credentials.xml");

            AtomicFileWriter.writeStoreAtomically(preserved, "<environments/>", AtomicFileWriter.FileMode.PRESERVE);
            AtomicFileWriter.writeStoreAtomically(ownerOnly, "<credentials/>", AtomicFileWriter.FileMode.OWNER_ONLY);

            assertThat(Files.readString(preserved)).isEqualTo("<environments/>");
            assertThat(Files.readString(ownerOnly)).isEqualTo("<credentials/>");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(preserved))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(ownerOnly))).isEqualTo("rw-------");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void storeWriteLeavesNoTempFileWhenTheWriteFails() throws Exception {
        Path dir = Files.createTempDirectory("kortty-store-write-failure");
        try {
            Path file = dir.resolve("credentials.xml");
            Files.writeString(file, "original content");

            expectThrows(NullPointerException.class, () -> AtomicFileWriter.writeStoreAtomically(
                file, null, AtomicFileWriter.FileMode.OWNER_ONLY));
            // A copy whose source vanished fails after the temp file was created.
            expectThrows(IOException.class, () -> AtomicFileWriter.copyStoreAtomically(
                dir.resolve("missing.xml"), file, AtomicFileWriter.FileMode.OWNER_ONLY));

            assertThat(Files.readString(file)).isEqualTo("original content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void copyStoreAtomicallyReplacesContentAndAppliesMode() throws Exception {
        Path dir = Files.createTempDirectory("kortty-store-copy");
        try {
            Path source = Files.writeString(dir.resolve("restored.xml"), "restored content");
            Path secret = Files.writeString(dir.resolve("master.key"), "old salt and hash");
            Path plain = Files.writeString(dir.resolve("themes.xml"), "old themes");
            requirePosix(secret);
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
            Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-r--r--"));

            AtomicFileWriter.copyStoreAtomically(source, secret, AtomicFileWriter.FileMode.OWNER_ONLY);
            AtomicFileWriter.copyStoreAtomically(source, plain, AtomicFileWriter.FileMode.PRESERVE);

            assertThat(Files.readString(secret)).isEqualTo("restored content");
            assertThat(Files.readString(plain)).isEqualTo("restored content");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secret))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(plain))).isEqualTo("rw-r--r--");
            assertThat(Files.readString(source)).isEqualTo("restored content");
            assertThat(listTempFiles(dir)).isEmpty();
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void restrictToOwnerTightensAnOwnedDirectoryAndFileOnce() throws Exception {
        Path dir = Files.createTempDirectory("kortty-restrict-owner");
        try {
            requirePosix(dir);
            Path configDir = Files.createDirectory(dir.resolve(".kortty"));
            Path file = Files.writeString(configDir.resolve("connections.xml"), "<connections/>");
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

            assertThat(AtomicFileWriter.restrictToOwner(configDir)).isTrue();
            assertThat(AtomicFileWriter.restrictToOwner(file)).isTrue();

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(configDir))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            // Already owner-only: nothing to change (and nothing to log at startup).
            assertThat(AtomicFileWriter.restrictToOwner(configDir)).isFalse();
            assertThat(AtomicFileWriter.restrictToOwner(file)).isFalse();
        } finally {
            deleteTree(dir);
        }
    }

    private static void requirePosix(Path path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            throw new SkipException("POSIX file attributes are not supported on this platform");
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
