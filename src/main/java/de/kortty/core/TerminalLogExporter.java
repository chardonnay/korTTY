package de.kortty.core;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Writes copies of terminal logs to another place, optionally in another compression and optionally
 * cut down to a time range.
 *
 * <p>The source is never modified. Every export is written to a temporary file beside its target and
 * only moved into place once it is complete, so an interrupted or failed export never leaves a
 * half-written file under the name the user asked for, and a cancelled one leaves nothing behind.</p>
 *
 * <p>Two things are refused on purpose. A file a logger still has open would hand out a copy that
 * stops mid-entry, so it is reported {@link Status#LIVE}. A copy written into a log folder under a
 * name the retention sweep recognises would be deleted by the sweep once it expires, which surprises a
 * user who only wanted a copy; a copy made with a time range carries a name the sweep does not own,
 * so it is always safe to keep there.</p>
 */
public final class TerminalLogExporter {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogExporter.class);

    /**
     * Copy block size. Large enough that checking for cancellation per block costs nothing, small
     * enough that a cancel on a large file takes effect within milliseconds.
     */
    private static final int BLOCK_BYTES = 64 * 1024;

    /** Progress inside one file is reported at most this often, so a fast copy does not flood the UI thread. */
    private static final long PROGRESS_STEP_BYTES = 256 * 1024;

    /** Hidden, so a leftover from a crash is never listed as a log by the manager. */
    private static final String TEMP_PREFIX = ".kortty-export-";

    private static final String CUT_MARKER = "-cut-";

    /** The {@link Result#entriesKept()} value for a copy that was not read line by line. */
    public static final long NOT_COUNTED = -1L;

    /** How an export ended. */
    public enum Status {
        /** Written to the target. */
        OK,
        /** A time range was given and no entry in the file falls inside it; nothing was written. */
        EMPTY_RANGE,
        /** The target exists and overwriting was not asked for. */
        EXISTS,
        /** The target is the source file itself. */
        SAME_FILE,
        /** A logger still has the file open, or it was written moments ago. */
        LIVE,
        /** Without a time range, the copy would be deleted by the retention sweep. */
        WOULD_BE_SWEPT,
        /** The caller cancelled; nothing was written. */
        CANCELLED,
        /** Something went wrong; {@link Result#error()} says what. */
        FAILED
    }

    /**
     * The outcome of one export.
     *
     * @param entriesKept the number of entries written for a time-range export, {@link #NOT_COUNTED}
     *                    for a plain copy (which is not read line by line), 0 otherwise
     * @param error       the reason for {@link Status#FAILED}, {@code null} otherwise
     */
    public record Result(Path source, Path target, Status status, long bytesWritten, long entriesKept,
                         String error) {
    }

    /**
     * A snapshot of a batch export. {@code bytesDone} and {@code bytesTotal} count source bytes as read
     * from disk, so a compressed source reports its compressed size.
     */
    public record Progress(int filesDone, int filesTotal, long bytesDone, long bytesTotal, String currentName) {
    }

    private TerminalLogExporter() {
    }

    /**
     * The file name an export of {@code sourceName} gets.
     *
     * <p>The source's compression suffix is replaced by the target's. A time range is recorded in the
     * name as {@code -cut-<tag>} placed before the format extension, so a user can tell at a glance
     * which copy holds a slice and which holds the whole file. Such a name is deliberately not a
     * korTTY log name, so the manager does not list it as one and the sweep never touches it.</p>
     *
     * @param range an open or {@code null} range names the file as a whole copy
     */
    public static String targetName(String sourceName, TerminalLogNaming.Compression target, LogTimeRange range) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(target, "target");
        TerminalLogNaming.Compression sourceCompression = TerminalLogNaming.Compression.ofName(sourceName);
        String base = sourceName.substring(0, sourceName.length() - sourceCompression.suffix.length());
        LogTimeRange effective = effectiveRange(range);
        if (effective != null) {
            int dot = base.lastIndexOf('.');
            String head = dot > 0 ? base.substring(0, dot) : base;
            String extension = dot > 0 ? base.substring(dot) : "";
            base = head + CUT_MARKER + effective.fileNameTag() + extension;
        }
        return base + target.suffix;
    }

    /**
     * Whether a whole-file copy at {@code target} would be swept by the retention sweep.
     *
     * <p>Independent of any retention value: a folder without retention today can have one tomorrow,
     * and the copy would then vanish. Only owned archives are candidates, and only when they sit
     * directly in one of the folders the sweep runs over.</p>
     *
     * @param logFolders the log folders, by real path
     */
    public static boolean wouldBeSwept(Path target, Collection<Path> logFolders) {
        if (target == null || logFolders == null || logFolders.isEmpty()) {
            return false;
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        Path parent = absolute.getParent();
        if (fileName == null || parent == null || !TerminalLogNaming.isOwnedArchive(fileName.toString())) {
            return false;
        }
        Path realParent = canonical(parent);
        for (Path folder : logFolders) {
            if (folder != null && canonical(folder).equals(realParent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exports one log to {@code target}.
     *
     * <p>The checks run in a fixed order, and the first that applies decides: the source must be a
     * regular file, not live, not the target itself; an existing target is kept unless overwriting was
     * asked for; a whole-file copy must not land where the sweep would take it.</p>
     *
     * @param range       the entries to keep, or {@code null} for a byte-for-byte copy in the new compression
     * @param logFolders  the log folders the sweep runs over, used for the swept check
     * @param bytesDone   receives the cumulative source bytes read, so the caller can show progress
     * @param cancelled   polled between blocks and lines; {@code null} means never cancelled
     */
    public static Result exportOne(TerminalLogFile src, Path target, TerminalLogNaming.Compression c,
                                   boolean overwrite, LogTimeRange range, Collection<Path> logFolders,
                                   LongConsumer bytesDone, BooleanSupplier cancelled) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(c, "target compression");
        LogTimeRange effective = effectiveRange(range);
        LongConsumer progress = bytesDone != null ? bytesDone : bytes -> { };
        BooleanSupplier stop = cancelled != null ? cancelled : () -> false;
        Path source = src.path();
        try {
            if (!Files.isRegularFile(source)) {
                return failed(src, target, "Not a regular file: " + src.name());
            }
            if (TerminalLogLiveness.isLive(source, src.compression(), currentModified(src), Instant.now())) {
                return outcome(src, target, Status.LIVE);
            }
            if (canonical(source).equals(canonical(target))) {
                return outcome(src, target, Status.SAME_FILE);
            }
            if (!overwrite && Files.exists(target)) {
                return outcome(src, target, Status.EXISTS);
            }
            if (effective == null && wouldBeSwept(target, logFolders)) {
                return outcome(src, target, Status.WOULD_BE_SWEPT);
            }
            if (effective != null && effective.isInvalid()) {
                return outcome(src, target, Status.EMPTY_RANGE);
            }
            return write(src, target, c, overwrite, effective, progress, stop);
        } catch (RuntimeException e) {
            return failed(src, target, messageOf(e));
        }
    }

    /**
     * Exports every source to {@code targetDir} under the name {@link #targetName} gives it.
     *
     * <p>Files are independent: one failing does not stop the batch. A file the range cannot touch is
     * reported {@link Status#EMPTY_RANGE} from its name and listed times alone, without opening it, so
     * a large selection with a narrow range stays fast. Cancelling marks the remaining files
     * {@link Status#CANCELLED} without touching the disk.</p>
     *
     * @param progress  receives an update as each file starts, while it copies, and when it ends
     */
    public static List<Result> exportAll(List<TerminalLogFile> sources, Path targetDir,
                                         TerminalLogNaming.Compression c, boolean overwrite, LogTimeRange range,
                                         Collection<Path> logFolders, Consumer<Progress> progress,
                                         BooleanSupplier cancelled) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(c, "target compression");
        LogTimeRange effective = effectiveRange(range);
        Consumer<Progress> listener = progress != null ? progress : update -> { };
        BooleanSupplier stop = cancelled != null ? cancelled : () -> false;
        int total = sources.size();
        long bytesTotal = 0;
        for (TerminalLogFile src : sources) {
            bytesTotal += src.sizeBytes();
        }
        final long totalBytes = bytesTotal;
        List<Result> results = new ArrayList<>(total);
        long bytesBefore = 0;
        for (int i = 0; i < total; i++) {
            TerminalLogFile src = sources.get(i);
            Path target = targetDir.resolve(targetName(src.name(), c, effective));
            if (stop.getAsBoolean()) {
                results.add(outcome(src, target, Status.CANCELLED));
                continue;
            }
            if (effective != null && !effective.overlaps(src.startedAt(), src.endedAt())) {
                results.add(outcome(src, target, Status.EMPTY_RANGE));
            } else {
                int index = i;
                long base = bytesBefore;
                long size = src.sizeBytes();
                listener.accept(new Progress(index, total, base, totalBytes, src.name()));
                results.add(exportOne(src, target, c, overwrite, effective, logFolders,
                    bytes -> listener.accept(new Progress(index, total, base + Math.min(bytes, size),
                        totalBytes, src.name())),
                    stop));
            }
            bytesBefore += src.sizeBytes();
            listener.accept(new Progress(i + 1, total, bytesBefore, totalBytes, src.name()));
        }
        return results;
    }

    private static LogTimeRange effectiveRange(LogTimeRange range) {
        return range == null || range.isOpen() ? null : range;
    }

    private static FileTime currentModified(TerminalLogFile src) {
        try {
            // The listing may be minutes old; the file's own time is what the liveness grace period needs.
            return Files.getLastModifiedTime(src.path());
        } catch (IOException e) {
            return src.modified();
        }
    }

    private static Result write(TerminalLogFile src, Path target, TerminalLogNaming.Compression c,
                                boolean overwrite, LogTimeRange range, LongConsumer progress,
                                BooleanSupplier cancelled) {
        Path temp = null;
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, TEMP_PREFIX, c.suffix.isEmpty() ? ".tmp" : c.suffix);
            AtomicFileWriter.restrictToOwner(temp);
            long kept;
            if (range == null) {
                copyWhole(src, temp, c, progress, cancelled);
                kept = NOT_COUNTED;
            } else {
                kept = copyInRange(src, temp, c, range, progress, cancelled);
                if (kept == 0) {
                    return outcome(src, target, Status.EMPTY_RANGE);
                }
            }
            if (cancelled.getAsBoolean()) {
                return outcome(src, target, Status.CANCELLED);
            }
            if (publish(temp, target, overwrite) == Status.EXISTS) {
                return outcome(src, target, Status.EXISTS);
            }
            return new Result(src.path(), target, Status.OK, Files.size(target), kept, null);
        } catch (TerminalLogTrimmer.CancelledException e) {
            return outcome(src, target, Status.CANCELLED);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not export {}: {}", src.name(), messageOf(e));
            return failed(src, target, messageOf(e));
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void copyWhole(TerminalLogFile src, Path temp, TerminalLogNaming.Compression c,
                                  LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        try (ProgressInputStream raw = openCounted(src, progress);
             InputStream in = decode(raw, src.compression());
             OutputStream out = TerminalLogCodecs.openOutput(temp, c)) {
            byte[] block = new byte[BLOCK_BYTES];
            int n;
            while ((n = in.read(block)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new TerminalLogTrimmer.CancelledException();
                }
                out.write(block, 0, n);
            }
            raw.drain();
            raw.reportFinal();
        }
    }

    private static long copyInRange(TerminalLogFile src, Path temp, TerminalLogNaming.Compression c,
                                    LogTimeRange range, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        try (ProgressInputStream raw = openCounted(src, progress);
             InputStream in = decode(raw, src.compression());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             Writer writer = new OutputStreamWriter(TerminalLogCodecs.openOutput(temp, c), StandardCharsets.UTF_8)) {
            // The connection's slug is the only name the file itself does not carry; a source header overrides it.
            long kept = TerminalLogTrimmer.trim(reader, writer, src.format(), src.parsed().slug(), range, cancelled);
            raw.drain();
            raw.reportFinal();
            return kept;
        }
    }

    /**
     * Moves a finished temporary file into place. Without overwriting, the target is created as a hard
     * link to the temporary file, which fails atomically when the target exists; that is the only way
     * to rule out a race between checking and writing.
     */
    private static Status publish(Path temp, Path target, boolean overwrite) throws IOException {
        if (overwrite) {
            AtomicFileWriter.moveIntoPlaceRetryingOnce(temp, target);
            return Status.OK;
        }
        try {
            Files.createLink(target, temp);
        } catch (FileAlreadyExistsException e) {
            return Status.EXISTS;
        } catch (UnsupportedOperationException | FileSystemException e) {
            // No hard links here (FAT, some network shares). A move without REPLACE_EXISTING still
            // refuses to clobber, though the check is then not atomic with the write.
            try {
                Files.move(temp, target);
            } catch (FileAlreadyExistsException existing) {
                return Status.EXISTS;
            }
            return Status.OK;
        }
        deleteQuietly(temp);
        return Status.OK;
    }

    private static InputStream decode(InputStream raw, TerminalLogNaming.Compression compression) throws IOException {
        // Mirrors TerminalLogCodecs.openInput, which opens the path itself and so cannot be handed the
        // counting stream; the counting has to sit below the decompressor to report file bytes.
        return switch (compression) {
            case BZIP2 -> new BZip2CompressorInputStream(new BufferedInputStream(raw), true);
            case GZIP -> new GZIPInputStream(new BufferedInputStream(raw, BLOCK_BYTES), BLOCK_BYTES);
            case ZSTD -> new ZstdInputStreamNoFinalizer(new BufferedInputStream(raw, BLOCK_BYTES));
            case NONE -> raw;
        };
    }

    private static ProgressInputStream openCounted(TerminalLogFile src, LongConsumer progress) throws IOException {
        return new ProgressInputStream(Files.newInputStream(src.path()), progress, src.sizeBytes());
    }

    private static Result outcome(TerminalLogFile src, Path target, Status status) {
        return new Result(src.path(), target, status, 0, 0, null);
    }

    private static Result failed(TerminalLogFile src, Path target, String message) {
        return new Result(src.path(), target, Status.FAILED, 0, 0, message);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Could not remove temporary export file {}: {}", path.getFileName(), e.getMessage());
        }
    }

    /** The real path of a file, or of its nearest existing ancestor with the rest appended. */
    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException | RuntimeException e) {
            Path parent = absolute.getParent();
            Path name = absolute.getFileName();
            if (parent == null || name == null) {
                return absolute;
            }
            return canonical(parent).resolve(name);
        }
    }

    /**
     * Counts the bytes taken from the file itself, below any decompression, so progress follows the
     * work left on disk rather than the much larger decompressed size.
     */
    private static final class ProgressInputStream extends FilterInputStream {

        private final LongConsumer progress;
        private final long limit;
        private long count;
        private long reported;

        ProgressInputStream(InputStream in, LongConsumer progress, long limit) {
            super(in);
            this.progress = progress;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                advance(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                advance(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                advance(skipped);
            }
            return skipped;
        }

        /** Reads what the decompressor left unread, so the count ends at the file's real size. */
        void drain() throws IOException {
            byte[] scratch = new byte[BLOCK_BYTES];
            while (read(scratch, 0, scratch.length) != -1) {
                // Only the counting matters here.
            }
        }

        void reportFinal() {
            if (count != reported) {
                report();
            }
        }

        private void advance(long n) {
            count += n;
            if (count - reported >= PROGRESS_STEP_BYTES) {
                report();
            }
        }

        private void report() {
            reported = count;
            // Capped at the listed size: a file that grew since the listing would otherwise report past 100%.
            progress.accept(Math.min(count, limit));
        }
    }
}
