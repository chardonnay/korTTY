package de.kortty.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Replaces a file's content without ever truncating it in place: a crash, disk-full error, or
 * kill mid-write must never leave a half-written file behind for the next read to choke on.
 *
 * <p>{@link #writeStringAtomically} is the light variant for editor buffers and the session
 * journal's hot paths. The {@code *StoreAtomically} variants are for korTTY's own data stores
 * ({@code connections.xml}, {@code credentials.xml}, {@code master.key} and so on): they also flush
 * the new content to the disk before the rename, so a power loss right after the save cannot leave
 * an empty file under the old name, and they can tighten the file to owner-only permissions.
 */
public final class AtomicFileWriter {

    /** How the permissions of a replaced store file are chosen. */
    public enum FileMode {
        /** Keep the replaced file's permissions; a new file is created owner-only. */
        PRESERVE,
        /** Always {@code rw-------}, also when the replaced file was readable by others. */
        OWNER_ONLY
    }

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    /** A virus scanner briefly holding the fresh temp file open makes the rename fail on Windows. */
    private static final long MOVE_RETRY_DELAY_MILLIS = 100;

    @FunctionalInterface
    private interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicFileWriter() {
    }

    public static void writeStringAtomically(Path filePath, String content) throws IOException {
        // Like Files.writeString: unencodable text (a lone surrogate) fails the write instead of
        // being replaced, and the original file stays as it was.
        replace(filePath, out -> out.write(encodeStrictly(content)), FileMode.PRESERVE, false);
    }

    /**
     * Replaces a data store's content: temp file in the same directory, flushed to the disk, then
     * renamed over the old file. The old file stays intact when anything before the rename fails,
     * and no temp file is left behind.
     */
    public static void writeStoreAtomically(Path filePath, String content, FileMode mode) throws IOException {
        replace(filePath, out -> out.write(content.getBytes(StandardCharsets.UTF_8)), mode, true);
    }

    /**
     * Replaces {@code target} with a copy of {@code source} the way {@link #writeStoreAtomically}
     * does, e.g. when a backup restores a store: a failed copy never leaves a truncated store.
     */
    public static void copyStoreAtomically(Path source, Path target, FileMode mode) throws IOException {
        replace(target, out -> Files.copy(source, out), mode, true);
    }

    /**
     * Tightens a file to {@code rw-------} or a directory to {@code rwx------}, but only when the
     * current user owns it and it is not owner-only yet. A no-op where the file system has no
     * POSIX permissions (Windows, where the user profile's ACL protects the data).
     *
     * @return whether the permissions were changed
     */
    public static boolean restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return false;
        }
        PosixFileAttributes attributes = view.readAttributes();
        if (!isOwnedByCurrentUser(attributes.owner())) {
            return false;
        }
        Set<PosixFilePermission> ownerOnly = attributes.isDirectory() ? OWNER_ONLY_DIRECTORY : OWNER_ONLY_FILE;
        if (attributes.permissions().equals(ownerOnly)) {
            return false;
        }
        view.setPermissions(ownerOnly);
        return true;
    }

    private static byte[] encodeStrictly(String content) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(content));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static boolean isOwnedByCurrentUser(UserPrincipal owner) {
        String currentUser = System.getProperty("user.name");
        return owner != null && currentUser != null && currentUser.equals(owner.getName());
    }

    private static void replace(Path filePath, ContentWriter writer, FileMode mode, boolean sync) throws IOException {
        // Files.move(..., REPLACE_EXISTING) replaces a symlink entry itself rather than the file it
        // points to, so resolve through symlinks first: the move must land on the real file, or a
        // symlinked config/dotfile would silently turn into a plain file.
        Path targetPath = Files.exists(filePath) ? filePath.toRealPath() : filePath.toAbsolutePath();
        Path parentDir = targetPath.getParent();
        Path tempFile = Files.createTempFile(parentDir, targetPath.getFileName().toString(), ".tmp");
        try {
            // Opening the existing temp file keeps its owner-only creation mode: the content is
            // never readable by others, not even for the moment before the permissions are set.
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream out = Channels.newOutputStream(channel);
                writer.writeTo(out);
                out.flush();
                if (sync) {
                    channel.force(true);
                }
            }
            if (mode == FileMode.OWNER_ONLY) {
                setPosixPermissionsIfSupported(tempFile, OWNER_ONLY_FILE);
            } else {
                copyPosixPermissionsIfPresent(targetPath, tempFile);
            }
            if (sync) {
                moveIntoPlaceRetryingOnce(tempFile, targetPath);
            } else {
                moveIntoPlace(tempFile, targetPath);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void moveIntoPlaceRetryingOnce(Path tempFile, Path targetPath) throws IOException {
        try {
            moveIntoPlace(tempFile, targetPath);
        } catch (AccessDeniedException firstFailure) {
            try {
                Thread.sleep(MOVE_RETRY_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw firstFailure;
            }
            try {
                moveIntoPlace(tempFile, targetPath);
            } catch (IOException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private static void moveIntoPlace(Path tempFile, Path targetPath) throws IOException {
        try {
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setPosixPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(permissions);
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
