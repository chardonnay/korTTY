package de.kortty.core;

import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpFileItem;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


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
    void listLocalIncludesParentEntryAndFileMetadata() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path childDirectory = Files.createDirectory(root.resolve("child"));
        Path childFile = Files.writeString(root.resolve("example.txt"), "hello");

        SftpFileTransferService service = new SftpFileTransferService();

        List<SftpFileItem> items = service.listLocal(root);

        assertThat(items.get(0).getName()).isEqualTo("..");
        assertThat(items.stream().anyMatch(item -> item.getPath().equals(childDirectory.toAbsolutePath().toString()) && !item.isFile())).isTrue();
        assertThat(items.stream().anyMatch(item -> item.getPath().equals(childFile.toAbsolutePath().toString()) && item.isFile())).isTrue();
    }

    @Test
    void renameAndDeleteLocalOperateOnFilesystem() throws Exception {
        Path sourceFile = Files.writeString(tempDir.resolve("before.txt"), "content");
        SftpFileTransferService service = new SftpFileTransferService();

        Path renamedFile = service.renameLocal(sourceFile, "after.txt");

        assertThat(Files.exists(sourceFile)).isFalse();
        assertThat(Files.exists(renamedFile)).isTrue();

        service.deleteLocal(renamedFile);

        assertThat(Files.exists(renamedFile)).isFalse();
    }

    @Test
    void copyLocalCopiesFilesAndDirectoriesRecursively() throws Exception {
        Path sourceDirectory = Files.createDirectory(tempDir.resolve("source"));
        Path nestedDirectory = Files.createDirectory(sourceDirectory.resolve("nested"));
        Files.writeString(sourceDirectory.resolve("root.txt"), "root");
        Files.writeString(nestedDirectory.resolve("nested.txt"), "nested");
        Path destinationDirectory = tempDir.resolve("target");

        SftpFileTransferService service = new SftpFileTransferService();

        service.copyLocal(List.of(sourceDirectory), destinationDirectory);

        assertThat(Files.exists(destinationDirectory.resolve("source").resolve("root.txt"))).isTrue();
        assertThat(Files.exists(destinationDirectory.resolve("source").resolve("nested").resolve("nested.txt"))).isTrue();
    }

    @Test
    void renameRemoteSupportsPathsWithoutParentSeparators() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        String newPath = service.renameRemote("report.txt", "renamed.txt");

        assertThat(newPath).isEqualTo("renamed.txt");
        assertThat(session.lastOldPath).isEqualTo("report.txt");
        assertThat(session.lastNewPath).isEqualTo("renamed.txt");
    }

    @Test
    void renameRemoteRejectsRootPath() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        IOException exception = expectThrows(IOException.class, () -> service.renameRemote("/", "renamed.txt"));

        assertThat(exception.getMessage()).isEqualTo("Cannot rename remote root path '/'");
    }

    @Test
    void renameRemoteRejectsBlankPath() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        IllegalArgumentException exception =
            expectThrows(IllegalArgumentException.class, () -> service.renameRemote("   ", "renamed.txt"));

        assertThat(exception.getMessage()).isEqualTo("Remote path must not be empty");
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

    private static final class RecordingSftpSession extends SFTPSession {
        private String lastOldPath;
        private String lastNewPath;

        private RecordingSftpSession() {
            super(new ServerConnection("test", "localhost", 22, "user"), "");
        }

        @Override
        public void connect() {
            // No-op for unit tests.
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String getCurrentDirectory() {
            return "/";
        }

        @Override
        public void renameFile(String oldPath, String newPath) {
            lastOldPath = oldPath;
            lastNewPath = newPath;
        }
    }
}
