package de.kortty.core.remote.edit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes edit folders ({@code kortty-remote-edit-<uuid>}) a crashed or killed korTTY left in the
 * system temp folder. Runs once at start, off the FX thread.
 *
 * <p>The temp folder is shared between users on Linux, so a folder is removed only when its name
 * is exactly one korTTY gives, it is a real folder (a link with that name is left alone), the
 * current user owns it, and nothing in it changed for {@link #MAXIMUM_AGE}, so the folder of an
 * edit another running korTTY still watches is kept. Removal never follows a link.
 */
public final class RemoteEditTempSweeper {

    private static final Logger logger = LoggerFactory.getLogger(RemoteEditTempSweeper.class);

    /** Folders untouched for this long are leftovers. */
    public static final Duration MAXIMUM_AGE = Duration.ofHours(24);

    private RemoteEditTempSweeper() {
    }

    /** Sweeps {@code java.io.tmpdir} for the current user; never throws. */
    public static void sweepAtStartup() {
        try {
            Path root = RemoteEditTempDirs.defaultRoot();
            UserPrincipal user = currentUser(root.getFileSystem());
            if (user == null) {
                return;
            }
            int removed = sweep(root, user, Instant.now(), MAXIMUM_AGE);
            if (removed > 0) {
                logger.info("Removed {} remote-edit folder(s) left in the temp folder by an earlier session", removed);
            }
        } catch (InvalidPathException | SecurityException e) {
            logger.warn("Could not inspect the temp folder for leftover remote-edit folders", e);
        } catch (RuntimeException e) {
            logger.warn("Unexpected failure while removing leftover remote-edit folders", e);
        }
    }

    /**
     * Removes the edit folders in {@code root} that {@code user} owns and that hold nothing changed
     * after {@code now - maximumAge}.
     *
     * @return the number of folders removed
     */
    public static int sweep(Path root, UserPrincipal user, Instant now, Duration maximumAge) {
        if (root == null || user == null) {
            return 0;
        }
        Path folder = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Instant cutoff = now.minus(maximumAge);
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, RemoteEditTempDirs.PREFIX + "*")) {
            for (Path entry : entries) {
                if (isStaleFolderOwnedBy(entry, user, cutoff) && RemoteEditTempDirs.deleteTree(entry)) {
                    removed++;
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            logger.warn("Could not list the temp folder while sweeping remote-edit folders", e);
        }
        return removed;
    }

    private static boolean isStaleFolderOwnedBy(Path entry, UserPrincipal user, Instant cutoff) {
        Path name = entry.getFileName();
        if (name == null || !RemoteEditTempDirs.NAME.matcher(name.toString()).matches()) {
            return false;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                return false;
            }
            if (!user.equals(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            return !newestChange(entry).isAfter(cutoff);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.debug("Could not inspect a remote-edit folder candidate", e);
            return false;
        }
    }

    /** The latest modification time of the folder and anything in it, links included as links. */
    private static Instant newestChange(Path folder) throws IOException {
        Instant[] newest = {Instant.EPOCH};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                record(attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                record(attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            private void record(BasicFileAttributes attrs) {
                Instant modified = attrs.lastModifiedTime().toInstant();
                if (modified.isAfter(newest[0])) {
                    newest[0] = modified;
                }
            }
        });
        return newest[0];
    }

    /** The user this process runs as, or {@code null} when the file system cannot tell. */
    static UserPrincipal currentUser(FileSystem fileSystem) {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) {
            return null;
        }
        try {
            return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(userName);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.debug("Could not resolve the current user; skipping the remote-edit folder sweep", e);
            return null;
        }
    }
}
