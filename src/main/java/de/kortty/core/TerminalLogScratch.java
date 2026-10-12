package de.kortty.core;

import de.kortty.core.remote.edit.RemoteEditTempDirs;
import de.kortty.core.remote.edit.RemoteEditTempSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * The private, decompressed copies of terminal log files the Logfile Manager's viewer reads.
 *
 * <p>A {@code .gz}, {@code .zst} or {@code .bz2} log cannot be read at a line offset, so the viewer
 * unpacks it once into a folder of its own and pages through that plain copy. The folder is
 * {@code kortty-logview-<uuid>} in the system temp folder and owner-only, because a copy of a
 * terminal log holds whatever the terminal showed, and the originals were private too.</p>
 *
 * <p>Every folder this process creates is remembered until it is deleted, so that quitting korTTY
 * can remove the copies still in use: korTTY quits through {@code Runtime.halt}, which runs no
 * shutdown hooks. Folders a crash left behind are removed at the next start, but only those nothing
 * has changed in for a day and whose lock file nobody holds, so a viewer another running korTTY
 * still has open keeps its copy however long it stays open. Readers on a folder are registered
 * here and closed before the folder goes, because Windows cannot delete a file that is still
 * open.</p>
 */
public final class TerminalLogScratch {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogScratch.class);

    /** The name prefix of every viewer folder. */
    public static final String PREFIX = "kortty-logview-";

    /** A folder name korTTY gave: the prefix and a random UUID. */
    static final Pattern NAME = Pattern.compile(Pattern.quote(PREFIX)
        + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Folders untouched for this long are leftovers of an earlier session. */
    static final Duration MAXIMUM_AGE = Duration.ofHours(24);

    /**
     * Free space that must remain after an unpack. The copy is an estimate, and the rest of the
     * system needs room too; a disk that fills up stops everything, not just the viewer.
     */
    private static final long SPACE_MARGIN = 512L * 1024 * 1024;

    /** The most one unpack may write; an archive that inflates past this is refused, not written. */
    private static final long MAXIMUM_BYTES = 8L * 1024 * 1024 * 1024;

    /** How much is read and written at a time: large enough to be fast, small enough to cancel promptly. */
    private static final int BLOCK = 1024 * 1024;

    /** Estimated uncompressed size of a non-gzip archive, as a multiple of its size on disk. */
    private static final long EXPANSION_ESTIMATE = 10L;

    /** Folders this process created and has not deleted yet. */
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

    /** Readers open on a folder's files, closed before the folder is deleted. */
    private static final Map<Path, Set<AutoCloseable>> READERS = new ConcurrentHashMap<>();

    /**
     * The lock file inside every viewer folder. This process holds a shared lock on it for the
     * folder's life, so the startup sweep of another korTTY can tell a folder in use from one a
     * crash left behind, however long the viewer has been open.
     */
    static final String LOCK_NAME = ".lock";

    /** The held lock channels, by folder; closing one releases the lock. */
    private static final Map<Path, FileChannel> LOCKS = new ConcurrentHashMap<>();

    /** Thrown when an unpack is cancelled; the partial copy is removed before it propagates. */
    public static final class CancelledException extends IOException {

        private static final long serialVersionUID = 1L;

        public CancelledException(String message) {
            super(message);
        }
    }

    private TerminalLogScratch() {
    }

    /**
     * Creates a new owner-only viewer folder in the system temp folder.
     *
     * <p>The mode is set when the folder is created, not afterwards, so there is no moment in which
     * another user could read a copy that has just been made. Where the file system has no POSIX
     * permissions, an ACL that only lets the owner in is set instead.</p>
     *
     * @throws IOException when it cannot be created or made private; nothing is left behind then
     */
    public static Path create() throws IOException {
        Path dir = RemoteEditTempDirs.defaultRoot().toAbsolutePath().normalize().resolve(PREFIX + UUID.randomUUID());
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(dir);
            try {
                RemoteEditTempDirs.restrictAclToOwner(dir);
            } catch (IOException | RuntimeException e) {
                RemoteEditTempDirs.deleteTree(dir);
                throw e instanceof IOException io ? io : new IOException(e);
            }
        }
        LIVE.add(dir);
        holdLock(dir);
        return dir;
    }

    /** Takes the shared lock on the folder's lock file; best effort, a folder without it just ages. */
    private static void holdLock(Path dir) {
        try {
            FileChannel channel = FileChannel.open(dir.resolve(LOCK_NAME), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                channel.lock(0L, Long.MAX_VALUE, true);
            } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
                // The lock file alone cannot protect the folder; the sweep then falls back to age.
            }
            LOCKS.put(dir, channel);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not lock the log viewer folder {}: {}", dir.getFileName(), e.toString());
        }
    }

    /** Whether another party holds a folder's lock file — a viewer of a running korTTY. */
    static boolean isInUse(Path dir) {
        return TerminalLogLiveness.isLockHeld(dir.resolve(LOCK_NAME));
    }

    /**
     * Unpacks {@code archive} into {@code scratchDir} under the name it has without its compression
     * suffix, and returns the copy.
     *
     * <p>Before anything is written the free space is checked against an estimate of the unpacked
     * size, because a log that inflates ten times over can fill a disk that looked fine. The estimate
     * for gzip is the size the archive's own trailer records, which is only a hint: it is taken
     * modulo 4 GiB. Any copy that fails or is cancelled is removed again, so a half-written file
     * never looks like a complete log.</p>
     *
     * @param bytesOut receives the number of bytes written so far, after each block; may be {@code null}
     * @param cancelled polled before each block; may be {@code null} for never
     * @throws CancelledException when {@code cancelled} reports true; the partial copy is removed
     * @throws IOException when the space is not there, the copy would exceed 8 GiB, or the archive
     *                     cannot be read
     */
    public static Path decompress(Path archive, Path scratchDir, LongConsumer bytesOut, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(scratchDir, "scratchDir");
        if (!Files.isDirectory(scratchDir)) {
            throw new IOException("The log viewer folder " + scratchDir.getFileName() + " does not exist");
        }
        String name = archive.getFileName().toString();
        TerminalLogNaming.Compression compression = TerminalLogNaming.Compression.ofName(name);
        String plainName = name.substring(0, name.length() - compression.suffix.length());
        if (plainName.isBlank()) {
            throw new IOException("Not a terminal log file name: " + name);
        }
        Path target = key(scratchDir).resolve(plainName);
        if (target.equals(key(archive))) {
            throw new IllegalArgumentException("An unpacked copy would overwrite the file it is read from");
        }
        requireSpace(archive, name, scratchDir, compression);
        boolean complete = false;
        try {
            copy(archive, target, name, bytesOut, cancelled);
            AtomicFileWriter.restrictToOwner(target);
            complete = true;
            return target;
        } finally {
            if (!complete) {
                deleteQuietly(target);
            }
        }
    }

    /**
     * Remembers a reader open on files of {@code scratchDir}, so {@link #delete(Path)} closes it
     * before removing the folder. Registering the same reader twice has no further effect.
     */
    public static void register(Path scratchDir, AutoCloseable reader) {
        if (scratchDir == null || reader == null) {
            return;
        }
        READERS.computeIfAbsent(key(scratchDir), dir -> ConcurrentHashMap.<AutoCloseable>newKeySet()).add(reader);
    }

    /** Forgets a reader registered with {@link #register}; it is not closed by this. */
    public static void unregister(Path scratchDir, AutoCloseable reader) {
        if (scratchDir == null || reader == null) {
            return;
        }
        READERS.computeIfPresent(key(scratchDir), (dir, readers) -> {
            readers.remove(reader);
            return readers.isEmpty() ? null : readers;
        });
    }

    /**
     * Closes the readers registered on {@code scratchDir}, forgets the folder and deletes it with
     * everything in it. Only a folder with the viewer's own name is ever deleted; anything else is
     * refused, so a wrong path cannot take the user's files with it. Best effort, never throws.
     */
    public static void delete(Path scratchDir) {
        if (scratchDir == null) {
            return;
        }
        Path dir = key(scratchDir);
        Path name = dir.getFileName();
        if (name == null || !NAME.matcher(name.toString()).matches()) {
            logger.warn("Refusing to delete a folder korTTY did not create as a log viewer folder");
            return;
        }
        Set<AutoCloseable> readers = READERS.remove(dir);
        if (readers != null) {
            for (AutoCloseable reader : readers) {
                closeQuietly(reader);
            }
        }
        LIVE.remove(dir);
        FileChannel lock = LOCKS.remove(dir);
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException ignored) {
                // The lock is gone with the channel either way.
            }
        }
        RemoteEditTempDirs.deleteTree(dir);
    }

    /** Deletes every viewer folder this process created and has not deleted yet (at quit). */
    public static void deleteAllLive() {
        for (Path dir : List.copyOf(LIVE)) {
            delete(dir);
        }
    }

    /**
     * Removes the viewer folders a crashed or killed korTTY left in the system temp folder. Runs
     * once at start, off the FX thread. Never throws.
     */
    public static void sweepAtStartup() {
        try {
            Path root = RemoteEditTempDirs.defaultRoot();
            UserPrincipal user = currentUser(root.getFileSystem());
            if (user == null) {
                return;
            }
            int removed = sweep(root, user, Instant.now(), MAXIMUM_AGE);
            if (removed > 0) {
                logger.info("Removed {} log viewer folder(s) left in the temp folder by an earlier session", removed);
            }
        } catch (InvalidPathException | SecurityException e) {
            logger.warn("Could not inspect the temp folder for leftover log viewer folders", e);
        } catch (RuntimeException e) {
            logger.warn("Unexpected failure while removing leftover log viewer folders", e);
        }
    }

    /**
     * Removes the viewer folders in {@code root} that {@code user} owns and that nothing changed in
     * after {@code now - maximumAge}; the same rules as the remote-edit sweep, with this prefix.
     *
     * @return the number of folders removed
     */
    static int sweep(Path root, UserPrincipal user, Instant now, Duration maximumAge) {
        return RemoteEditTempSweeper.sweep(root, PREFIX, NAME, user, now, maximumAge, TerminalLogScratch::isInUse);
    }

    private static void requireSpace(Path archive, String name, Path scratchDir,
                                     TerminalLogNaming.Compression compression) throws IOException {
        long size = Files.size(archive);
        long estimate = compression == TerminalLogNaming.Compression.GZIP
            ? gzipSizeHint(archive, size) : saturatedMultiply(size, EXPANSION_ESTIMATE);
        long usable = Files.getFileStore(scratchDir).getUsableSpace();
        if (usable <= saturatedAdd(estimate, SPACE_MARGIN)) {
            throw new IOException("Not enough free disk space to unpack " + name + ": about "
                + mebibytes(estimate) + " MiB are needed plus a safety margin, " + mebibytes(usable) + " MiB are free");
        }
    }

    /**
     * The uncompressed size gzip records in its last four bytes, little-endian. It is kept modulo
     * 2^32, so it is only a hint; it is never allowed below the archive's own size.
     */
    private static long gzipSizeHint(Path archive, long size) throws IOException {
        long fallback = saturatedMultiply(size, EXPANSION_ESTIMATE);
        if (size < 4) {
            return fallback;
        }
        ByteBuffer trailer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            long position = size - 4;
            while (trailer.hasRemaining()) {
                if (channel.read(trailer, position + trailer.position()) < 0) {
                    return fallback;
                }
            }
        }
        return Math.max(Integer.toUnsignedLong(trailer.getInt(0)), size);
    }

    private static void copy(Path archive, Path target, String name, LongConsumer bytesOut,
                             BooleanSupplier cancelled) throws IOException {
        try (InputStream in = TerminalLogCodecs.openInput(archive);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] block = new byte[BLOCK];
            long written = 0;
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancelledException("Unpacking " + name + " was cancelled");
                }
                int read = in.read(block);
                if (read < 0) {
                    break;
                }
                if (read > MAXIMUM_BYTES - written) {
                    throw new IOException("The unpacked copy of " + name + " would exceed "
                        + (MAXIMUM_BYTES >> 30) + " GiB; it was not written");
                }
                out.write(block, 0, read);
                written += read;
                if (bytesOut != null) {
                    bytesOut.accept(written);
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable reader) {
        try {
            reader.close();
        } catch (Exception e) {
            logger.debug("A log reader did not close cleanly before its viewer folder was deleted: {}", e.toString());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | SecurityException e) {
            logger.debug("Could not remove a partial log copy: {}", e.toString());
        }
    }

    /** The user this process runs as, or {@code null} when the file system cannot tell. */
    private static UserPrincipal currentUser(FileSystem fileSystem) {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) {
            return null;
        }
        try {
            return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(userName);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            logger.debug("Could not resolve the current user; skipping the log viewer folder sweep", e);
            return null;
        }
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static long saturatedMultiply(long value, long factor) {
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private static long saturatedAdd(long value, long addend) {
        return value > Long.MAX_VALUE - addend ? Long.MAX_VALUE : value + addend;
    }

    private static long mebibytes(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
