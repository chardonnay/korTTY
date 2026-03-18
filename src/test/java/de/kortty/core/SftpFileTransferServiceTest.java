package de.kortty.core;

import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpFileItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpFileTransferServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void listLocalIncludesParentEntryAndFileMetadata() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path childDirectory = Files.createDirectory(root.resolve("child"));
        Path childFile = Files.writeString(root.resolve("example.txt"), "hello");

        SftpFileTransferService service = new SftpFileTransferService();

        List<SftpFileItem> items = service.listLocal(root);

        assertEquals("..", items.get(0).getName());
        assertTrue(items.stream().anyMatch(item -> item.getPath().equals(childDirectory.toAbsolutePath().toString()) && !item.isFile()));
        assertTrue(items.stream().anyMatch(item -> item.getPath().equals(childFile.toAbsolutePath().toString()) && item.isFile()));
    }

    @Test
    void renameAndDeleteLocalOperateOnFilesystem() throws Exception {
        Path sourceFile = Files.writeString(tempDir.resolve("before.txt"), "content");
        SftpFileTransferService service = new SftpFileTransferService();

        Path renamedFile = service.renameLocal(sourceFile, "after.txt");

        assertFalse(Files.exists(sourceFile));
        assertTrue(Files.exists(renamedFile));

        service.deleteLocal(renamedFile);

        assertFalse(Files.exists(renamedFile));
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

        assertTrue(Files.exists(destinationDirectory.resolve("source").resolve("root.txt")));
        assertTrue(Files.exists(destinationDirectory.resolve("source").resolve("nested").resolve("nested.txt")));
    }

    @Test
    void renameRemoteSupportsPathsWithoutParentSeparators() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        String newPath = service.renameRemote("report.txt", "renamed.txt");

        assertEquals("renamed.txt", newPath);
        assertEquals("report.txt", session.lastOldPath);
        assertEquals("renamed.txt", session.lastNewPath);
    }

    @Test
    void renameRemoteRejectsRootPath() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        IOException exception = assertThrows(IOException.class, () -> service.renameRemote("/", "renamed.txt"));

        assertEquals("Cannot rename remote root path '/'", exception.getMessage());
    }

    @Test
    void renameRemoteRejectsBlankPath() throws Exception {
        RecordingSftpSession session = new RecordingSftpSession();
        SftpFileTransferService service = new SftpFileTransferService();
        service.connect(session);

        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, () -> service.renameRemote("   ", "renamed.txt"));

        assertEquals("Remote path must not be empty", exception.getMessage());
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
