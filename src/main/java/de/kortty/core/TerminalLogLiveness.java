package de.kortty.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

/**
 * The one rule for "is this log still being written?", shared by the manager's listing, delete
 * and export so they can never disagree.
 *
 * <p>Four signals, any of which makes a file live:</p>
 * <ol>
 *   <li>a {@link TerminalLogger} in this process has it open;</li>
 *   <li>another process holds the lock sidecar korTTY's loggers keep beside the file they write
 *       ({@link TerminalLogNaming#lockFileFor}), so an exclusive {@code tryLock} on it fails;</li>
 *   <li>it is an archive whose plain original still exists and the archive changed within the
 *       last few minutes: the logger gzips a closed file under its final name and deletes the
 *       plain one only afterwards, so during that copy the archive is incomplete;</li>
 *   <li>it is uncompressed and changed within the last few minutes. A closed korTTY log is gzipped
 *       by default, so a plain file is either live, from a logger with compression off, or a
 *       leftover — the grace period covers an older korTTY without the sidecar.</li>
 * </ol>
 *
 * <p>What this cannot see: a foreign process writing without the sidecar whose last write is older
 * than the grace period. The delete confirmation names uncompressed files for that reason.</p>
 */
public final class TerminalLogLiveness {

    /** How long after its last write an uncompressed file (or an archive with its plain sibling) still counts as live. */
    public static final Duration RECENT_WRITE_GRACE = Duration.ofMinutes(5);

    private TerminalLogLiveness() {
    }

    public static boolean isLive(Path file, TerminalLogNaming.Compression compression,
                                 FileTime modified, Instant now) {
        if (file == null) {
            return false;
        }
        if (TerminalLogger.isLive(file)) {
            return true;
        }
        if (isLockedElsewhere(file)) {
            return true;
        }
        boolean recent = modified != null && now != null
            && modified.toInstant().isAfter(now.minus(RECENT_WRITE_GRACE));
        if (compression == TerminalLogNaming.Compression.NONE) {
            return recent;
        }
        return recent && isBeingCompressed(file, compression);
    }

    /**
     * True when some other process holds the lock sidecar of {@code file}. Without a sidecar there
     * is nothing to hold, so the answer is "no"; a sidecar a crash left behind is unlocked and
     * reads the same way.
     */
    static boolean isLockedElsewhere(Path file) {
        Path lockFile = TerminalLogNaming.lockFileFor(file);
        return Files.exists(lockFile) && isLockHeld(lockFile);
    }

    /**
     * Whether another party holds a lock on {@code lockFile}. The probe lock is released at once;
     * a missing file, a file system without locks, or any I/O trouble reads as "not held".
     */
    static boolean isLockHeld(Path lockFile) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, false);
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException e) {
            // This JVM already holds a lock on it — a logger or viewer of our own.
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the plain original of {@code archive} still sits next to it, as it does while the logger compresses. */
    static boolean isBeingCompressed(Path archive, TerminalLogNaming.Compression compression) {
        if (compression == null || compression == TerminalLogNaming.Compression.NONE) {
            return false;
        }
        String name = archive.getFileName().toString();
        if (!name.endsWith(compression.suffix)) {
            return false;
        }
        Path plain = archive.resolveSibling(name.substring(0, name.length() - compression.suffix.length()));
        return Files.exists(plain);
    }
}
