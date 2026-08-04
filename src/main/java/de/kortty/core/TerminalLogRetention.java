package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Deletes terminal log archives older than the configured number of days.
 *
 * <p>Daily rotation without a sweep means one file per connection per day forever, so this is what
 * keeps "the log must not grow too large" true over months rather than only over one session.</p>
 *
 * <p>Three independent rules stand between this and a file someone still needs:</p>
 * <ol>
 *   <li>Only {@code .gz} archives are candidates. The file currently being written is plain by
 *       construction, and rotation compresses only after the writer is closed.</li>
 *   <li>Files a live logger has open are skipped outright.</li>
 *   <li>The name must match {@link TerminalLogNaming#isOwnedArchive(String)}. A user is free to
 *       point the log directory at a folder that holds other things; nothing else is touched.</li>
 * </ol>
 */
public final class TerminalLogRetention {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogRetention.class);

    /** One sweep at a time per directory; two loggers in the same folder would otherwise race. */
    private static final Map<Path, Object> DIRECTORY_LOCKS = new HashMap<>();

    private TerminalLogRetention() {
    }

    /**
     * Removes archives whose encoded date is more than {@code retentionDays} before {@code today}.
     *
     * @param liveFiles files currently open for writing, never considered
     * @return how many files were deleted
     */
    public static int sweep(Path directory, int retentionDays, LocalDate today, Set<Path> liveFiles) {
        if (directory == null || retentionDays <= 0 || today == null
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        LocalDate cutoff = today.minusDays(retentionDays);
        Set<Path> live = liveFiles != null ? liveFiles : Collections.emptySet();
        int deleted = 0;
        int strays = 0;

        synchronized (lockFor(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    String name = file.getFileName().toString();
                    LocalDate date = TerminalLogNaming.archiveDate(name);
                    if (date == null) {
                        // An uncompressed leftover from a crash: ours, but deliberately kept.
                        // Losing a log to a tidy-up is worse than a stale file on disk.
                        if (!name.endsWith(SessionJournalLogCompressor.GZIP_SUFFIX)) {
                            strays++;
                        }
                        continue;
                    }
                    if (!date.isBefore(cutoff) || live.contains(file.toAbsolutePath().normalize())) {
                        continue;
                    }
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                    } catch (IOException e) {
                        logger.warn("Could not delete expired terminal log {}: {}", name, e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not sweep terminal log directory: {}", e.getMessage());
            }
        }

        if (deleted > 0 || strays > 0) {
            logger.info("Terminal log retention: {} archive(s) deleted, {} uncompressed leftover(s) kept",
                deleted, strays);
        }
        return deleted;
    }

    private static synchronized Object lockFor(Path directory) {
        return DIRECTORY_LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(), key -> new Object());
    }
}
