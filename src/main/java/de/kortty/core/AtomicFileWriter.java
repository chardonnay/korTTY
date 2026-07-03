package de.kortty.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;

/**
 * Replaces a file's content without ever truncating it in place: a crash, disk-full error, or
 * kill mid-write must never leave a half-written file behind for the next read to choke on.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void writeStringAtomically(Path filePath, String content) throws IOException {
        Path parentDir = filePath.toAbsolutePath().getParent();
        Path tempFile = Files.createTempFile(parentDir, filePath.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            copyPosixPermissionsIfPresent(filePath, tempFile);
            try {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // Files.createTempFile applies restrictive default permissions, so without this the replaced
    // file would silently lose its original (e.g. group/world-readable) permissions on POSIX systems.
    private static void copyPosixPermissionsIfPresent(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        PosixFileAttributeView sourceView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        PosixFileAttributeView targetView = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (sourceView == null || targetView == null) {
            return;
        }
        targetView.setPermissions(sourceView.readAttributes().permissions());
    }
}
