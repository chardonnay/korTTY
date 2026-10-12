package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Deletes terminal log files the Logfile Manager has selected, with the same safety rules the
 * retention sweep uses.
 *
 * <p>The manager shows a list the user picks from, so the list is not trusted as it stands: each file
 * is looked at again at the moment of deletion. A file that has been replaced by a symbolic link, moved
 * out of korTTY's naming, or reopened by a logger since the list was built is skipped, not deleted.
 * A batch never stops early, because one locked file should not leave the user wondering which of the
 * others went through.</p>
 */
public final class TerminalLogFiles {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogFiles.class);

    /** What happened to one file. */
    public enum DeleteStatus {
        DELETED,
        /** Gone before the delete ran, so there was nothing to do. */
        MISSING,
        /** Being written right now, so it was left alone. */
        SKIPPED_LIVE,
        /** Not named the way korTTY names its logs, so it is not korTTY's to delete. */
        SKIPPED_NOT_OWNED,
        /** A symbolic link, directory or other non-file; deleting it could remove something else. */
        SKIPPED_NOT_REGULAR,
        /** The file system refused; {@link DeleteOutcome#error()} says why. */
        FAILED
    }

    /** The outcome for one file, in the order the files were given. */
    public record DeleteOutcome(Path file, DeleteStatus status, String error) {
    }

    private TerminalLogFiles() {
    }

    /**
     * Deletes the files that are safe to delete and reports on each one.
     *
     * @param now the moment liveness is judged at; {@code null} means the current time
     */
    public static List<DeleteOutcome> delete(Collection<TerminalLogFile> files, Instant now) {
        List<DeleteOutcome> outcomes = new ArrayList<>();
        if (files == null) {
            return outcomes;
        }
        Instant moment = now != null ? now : Instant.now();
        for (TerminalLogFile file : files) {
            outcomes.add(deleteOne(file, moment));
        }
        return outcomes;
    }

    private static DeleteOutcome deleteOne(TerminalLogFile file, Instant now) {
        Path path = null;
        try {
            path = file.path();
            BasicFileAttributes attributes;
            try {
                // NOFOLLOW: a symbolic link must be refused here, not followed to whatever it points at.
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return new DeleteOutcome(path, DeleteStatus.MISSING, null);
            }
            if (!attributes.isRegularFile()) {
                return new DeleteOutcome(path, DeleteStatus.SKIPPED_NOT_REGULAR, null);
            }
            TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse(path.getFileName().toString());
            if (parsed == null) {
                return new DeleteOutcome(path, DeleteStatus.SKIPPED_NOT_OWNED, null);
            }
            // The attributes are read now, not taken from the listing, which may be minutes old.
            if (TerminalLogLiveness.isLive(path, parsed.compression(), attributes.lastModifiedTime(), now)) {
                return new DeleteOutcome(path, DeleteStatus.SKIPPED_LIVE, null);
            }
            return Files.deleteIfExists(path)
                ? new DeleteOutcome(path, DeleteStatus.DELETED, null)
                : new DeleteOutcome(path, DeleteStatus.MISSING, null);
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.warn("Could not delete terminal log {}: {}", path != null ? path.getFileName() : "?", message);
            return new DeleteOutcome(path, DeleteStatus.FAILED, message);
        }
    }
}
