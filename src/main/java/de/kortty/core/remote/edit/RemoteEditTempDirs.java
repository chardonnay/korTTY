package de.kortty.core.remote.edit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The private local folders remote files are edited in: {@code kortty-remote-edit-<uuid>} in the
 * system temp folder, created {@code rwx------} on POSIX systems and with an ACL that only lets
 * the owner in on Windows, before anything is written into them.
 *
 * <p>Every folder this process creates is remembered until it is deleted, so quitting korTTY can
 * remove the ones still in use: korTTY quits through {@code Runtime.halt}, which runs no shutdown
 * hooks and no {@code deleteOnExit}. Folders a crash left behind are removed at the next start by
 * {@link RemoteEditTempSweeper}. Deleting never follows a symbolic link.
 */
public final class RemoteEditTempDirs {

    private static final Logger logger = LoggerFactory.getLogger(RemoteEditTempDirs.class);

    /** The name prefix of every edit folder. */
    public static final String PREFIX = "kortty-remote-edit-";
    /** A folder name korTTY gave: the prefix and a random UUID. */
    static final Pattern NAME = Pattern.compile(Pattern.quote(PREFIX)
        + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

    private RemoteEditTempDirs() {
    }

    /** The system temp folder, where edit folders are created by default. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    /**
     * Creates a new owner-only edit folder in {@code root}.
     *
     * @throws IOException when it cannot be created or made private (it is removed again then)
     */
    public static Path create(Path root) throws IOException {
        Path dir = root.toAbsolutePath().normalize().resolve(PREFIX + UUID.randomUUID());
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(dir);
            try {
                restrictAclToOwner(dir);
            } catch (IOException | RuntimeException e) {
                deleteTree(dir);
                throw e instanceof IOException io ? io : new IOException(e);
            }
        }
        LIVE.add(dir);
        return dir;
    }

    /** Deletes {@code dir} and everything in it, never following links; best effort. */
    public static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        LIVE.remove(dir);
        deleteTree(dir);
    }

    /** Deletes every edit folder this process created and has not deleted yet (at quit). */
    public static void deleteAllLive() {
        for (Path dir : List.copyOf(LIVE)) {
            delete(dir);
        }
    }

    /** The edit folders this process created and has not deleted yet. */
    static Set<Path> live() {
        return Set.copyOf(LIVE);
    }

    /**
     * Deletes the tree at {@code root} bottom-up. A link is deleted as a link; its target is never
     * entered or touched.
     */
    static boolean deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        boolean[] complete = {true};
        try {
            // walkFileTree without FOLLOW_LINKS reports a link as a file and never enters it.
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteQuietly(file, complete);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    deleteQuietly(file, complete);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir, complete);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.debug("Could not remove the edit folder {}: {}", root, e.toString());
            return false;
        }
        return complete[0];
    }

    private static void deleteQuietly(Path path, boolean[] complete) {
        try {
            Files.deleteIfExists(path);
        } catch (NoSuchFileException ignored) {
            // Gone already.
        } catch (IOException | SecurityException e) {
            complete[0] = false;
            logger.debug("Could not delete {}: {}", path, e.toString());
        }
    }

    private static void restrictAclToOwner(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        AclEntry ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
            .build();
        view.setAcl(List.of(ownerOnly));
    }
}
