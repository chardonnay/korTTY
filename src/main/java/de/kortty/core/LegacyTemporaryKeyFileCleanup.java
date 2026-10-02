package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Removes private-key files that earlier korTTY versions left in the system temp folder.
 *
 * <p>Before temporary SSH keys were parsed in memory ({@link TemporarySshKeyMaterial}), every
 * connect, split and SFTP open wrote the key to a {@code kortty_temp_key_<n>.key} file, and every
 * scheduled job with a temporary key wrote a {@code kortty_scheduler_key_<n>.key} file. None were
 * ever deleted, because korTTY quits through {@code Runtime.halt}, which skips
 * {@code deleteOnExit}. The scheduler file is still written today for the external {@code ssh} of
 * Rsync jobs and is deleted when the job ends; this sweep also catches one a crash left behind.
 *
 * <p>The temp folder is shared between users on Linux, so a file is deleted only when all of these
 * hold: its name matches {@link #KEY_FILE_NAME} exactly, it is a regular file (links are never
 * followed), the current user owns it, and it is older than {@link #MINIMUM_AGE}, so a key another
 * running korTTY has just written is left alone. Everything is best effort, and the summary log
 * line carries only a count.
 */
public final class LegacyTemporaryKeyFileCleanup {

    private static final Logger logger = LoggerFactory.getLogger(LegacyTemporaryKeyFileCleanup.class);

    /** The names {@code File.createTempFile} / {@code Files.createTempFile} gave those files. */
    static final Pattern KEY_FILE_NAME = Pattern.compile("kortty_(?:temp|scheduler)_key_\\d{1,20}\\.key");
    static final Duration MINIMUM_AGE = Duration.ofMinutes(5);
    private static final String KEY_FILE_GLOB = "kortty_*_key_*.key";

    private LegacyTemporaryKeyFileCleanup() {
    }

    /** Sweeps {@code java.io.tmpdir} for the current user; never throws. */
    public static void cleanupAtStartup() {
        try {
            String configured = System.getProperty("java.io.tmpdir");
            if (configured == null || configured.isBlank()) {
                return;
            }
            Path tempDirectory = Path.of(configured);
            UserPrincipal currentUser = currentUser(tempDirectory.getFileSystem());
            if (currentUser == null) {
                return;
            }
            int removed = cleanup(tempDirectory, currentUser, Instant.now());
            if (removed > 0) {
                logger.info("Removed {} temporary SSH key file(s) left in the temp folder by an earlier session",
                    removed);
            }
        } catch (InvalidPathException | SecurityException e) {
            logger.warn("Could not inspect the temp folder for leftover temporary SSH key files", e);
        } catch (RuntimeException e) {
            logger.warn("Unexpected failure while removing leftover temporary SSH key files", e);
        }
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
            logger.debug("Could not resolve the current user; skipping the temporary SSH key sweep", e);
            return null;
        }
    }

    /**
     * Deletes the leftover key files in {@code tempDirectory} that {@code currentUser} owns and that
     * were last modified before {@code now - MINIMUM_AGE}.
     *
     * @return the number of files deleted
     */
    static int cleanup(Path tempDirectory, UserPrincipal currentUser, Instant now) {
        if (tempDirectory == null || currentUser == null) {
            return 0;
        }
        Path root = tempDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            logger.warn("Refusing to follow a symbolic link while sweeping temporary SSH key files");
            return 0;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }

        Instant cutoff = now.minus(MINIMUM_AGE);
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, KEY_FILE_GLOB)) {
            for (Path entry : entries) {
                if (isStaleKeyFileOwnedBy(entry, currentUser, cutoff)) {
                    try {
                        // Files.delete removes a link itself, never its target; the entry was
                        // just checked to be a regular file anyway.
                        Files.delete(entry);
                        removed++;
                    } catch (IOException | SecurityException e) {
                        logger.debug("Could not delete a leftover temporary SSH key file", e);
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            logger.warn("Could not list the temp folder while sweeping temporary SSH key files", e);
        }
        return removed;
    }

    private static boolean isStaleKeyFileOwnedBy(Path entry, UserPrincipal currentUser, Instant cutoff) {
        Path fileName = entry.getFileName();
        if (fileName == null || !KEY_FILE_NAME.matcher(fileName.toString()).matches()) {
            return false;
        }
        try {
            BasicFileAttributes attributes =
                Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return false;
            }
            if (!attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                return false;
            }
            return currentUser.equals(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.debug("Could not inspect a temporary SSH key file candidate", e);
            return false;
        }
    }
}
