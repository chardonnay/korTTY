package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


/** The static helpers that remain of {@link SftpFileTransferService} (D19). */
class SftpFileTransferServiceTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-sftp-transfer-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete temp path " + path, e);
                    }
                });
        }
    }

    @Test
    void resolveSiblingRemoteFilePathKeepsTargetInSameDirectory() {
        assertThat(SftpFileTransferService.resolveSiblingRemoteFilePath("/etc/app.conf", "app2.conf"))
            .isEqualTo("/etc/app2.conf");
        assertThat(SftpFileTransferService.resolveSiblingRemoteFilePath("/app.conf", "app2.conf"))
            .isEqualTo("/app2.conf");
        assertThat(SftpFileTransferService.resolveSiblingRemoteFilePath("app.conf", "app2.conf"))
            .isEqualTo("app2.conf");
    }

    @Test
    void resolveSiblingRemoteFilePathRejectsUnsafeFileNames() {
        assertThat(expectThrows(IllegalArgumentException.class,
            () -> SftpFileTransferService.resolveSiblingRemoteFilePath("/etc/app.conf", "")).getMessage())
            .isEqualTo("File name must not be empty");
        assertThat(expectThrows(IllegalArgumentException.class,
            () -> SftpFileTransferService.resolveSiblingRemoteFilePath("/etc/app.conf", "/tmp/app.conf")).getMessage())
            .isEqualTo("File name must not contain path separators");
        assertThat(expectThrows(IllegalArgumentException.class,
            () -> SftpFileTransferService.resolveSiblingRemoteFilePath("/etc/app.conf", "tmp\\app.conf")).getMessage())
            .isEqualTo("File name must not contain path separators");
        assertThat(expectThrows(IllegalArgumentException.class,
            () -> SftpFileTransferService.resolveSiblingRemoteFilePath("/etc/app.conf", "..")).getMessage())
            .isEqualTo("File name must not be '.' or '..'");
    }

    @Test
    void validateEntryNameAcceptsOneNameInTheFolderShown() {
        assertThat(SftpFileTransferService.validateEntryName("new name.txt")).isEqualTo("new name.txt");
        assertThat(SftpFileTransferService.validateEntryName("  .hidden ")).isEqualTo(".hidden");
        assertThat(SftpFileTransferService.validateEntryName("a:b")).isEqualTo("a:b");

        for (String invalid : new String[] {"", "   ", ".", "..", "a/b", "a\\b", "/", "nul\0byte"}) {
            expectThrows(IllegalArgumentException.class, () -> SftpFileTransferService.validateEntryName(invalid));
        }
        expectThrows(IllegalArgumentException.class, () -> SftpFileTransferService.validateEntryName(null));
    }

    @Test
    void renameLocalEntryNeverReplacesAnotherEntry() throws Exception {
        Path source = Files.writeString(tempDir.resolve("draft.txt"), "draft");
        Path existing = Files.writeString(tempDir.resolve("final.txt"), "final");

        expectThrows(FileAlreadyExistsException.class,
            () -> SftpFileTransferService.renameLocalEntry(source, "final.txt"));
        assertThat(Files.readString(source)).isEqualTo("draft");
        assertThat(Files.readString(existing)).isEqualTo("final");

        // A name that points elsewhere is refused before anything moves.
        expectThrows(IllegalArgumentException.class,
            () -> SftpFileTransferService.renameLocalEntry(source, "../escaped.txt"));
        assertThat(Files.exists(source)).isTrue();

        Path renamed = SftpFileTransferService.renameLocalEntry(source, " report.txt ");
        assertThat(renamed).isEqualTo(tempDir.resolve("report.txt"));
        assertThat(Files.readString(renamed)).isEqualTo("draft");
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    void renameLocalEntryChangesOnlyTheCase() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("case"));
        Path source = Files.writeString(folder.resolve("readme.txt"), "text");

        // On a case-insensitive file system (macOS, Windows) the new name "exists" as the file itself.
        SftpFileTransferService.renameLocalEntry(source, "README.txt");

        try (var entries = Files.list(folder)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("README.txt");
        }
        assertThat(Files.readString(folder.resolve("README.txt"))).isEqualTo("text");
    }

    @Test
    void renameLocalEntryRefusesTheFileALinkPointsTo() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("links"));
        Path file = Files.writeString(folder.resolve("a.txt"), "text");
        Path link;
        try {
            link = Files.createSymbolicLink(folder.resolve("link"), file.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e);
        }

        // 'link' and 'a.txt' are the same file to Files.isSameFile, but two entries of the folder.
        expectThrows(FileAlreadyExistsException.class, () -> SftpFileTransferService.renameLocalEntry(link, "a.txt"));

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(file)).isEqualTo("text");
        try (var entries = Files.list(folder)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                .containsExactly("a.txt", "link");
        }
    }

    @Test
    void renameLocalEntryRefusesAHardLinkOfTheSameFile() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("hard"));
        Path file = Files.writeString(folder.resolve("draft.txt"), "text");
        Path second;
        try {
            second = Files.createLink(folder.resolve("copy.txt"), file);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Hard links are not available here: " + e);
        }

        expectThrows(FileAlreadyExistsException.class, () -> SftpFileTransferService.renameLocalEntry(file, "copy.txt"));

        assertThat(Files.readString(file)).isEqualTo("text");
        assertThat(Files.readString(second)).isEqualTo("text");
        try (var entries = Files.list(folder)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                .containsExactly("copy.txt", "draft.txt");
        }
    }
}
