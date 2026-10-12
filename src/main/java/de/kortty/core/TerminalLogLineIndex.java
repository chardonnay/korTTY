package de.kortty.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Where the lines of a log file start, kept sparsely so a file of millions of lines costs kilobytes.
 *
 * <p>Storing the start of every line would cost about as much as the log itself is large in line
 * count, and the viewer needs only the lines it shows. So the index keeps a checkpoint (line number
 * and byte offset of the line's start) every {@value #CHECKPOINT_LINES} lines or every 1 MiB,
 * whichever comes first. Looking a line up is a binary search for the nearest checkpoint, then a
 * forward scan of at most one checkpoint's worth of bytes plus one line. That bound holds however
 * long the file is, which is what makes paging through a multi-gigabyte log responsive.</p>
 *
 * <p>An index is built once over a fixed range of a file and never changes afterwards, so it can be
 * shared between threads freely. It describes the bytes it scanned: a file that is later rotated or
 * compressed no longer matches it, and the reader reports that rather than returning wrong lines.</p>
 */
public final class TerminalLogLineIndex {

    /** Lines between two checkpoints at most. */
    private static final int CHECKPOINT_LINES = 256;

    /** Bytes between two checkpoints at most (a single longer line still gets one checkpoint). */
    private static final long CHECKPOINT_BYTES = 1024L * 1024L;

    /** One scan read at a time: large enough to be quick, small enough that a cancel is prompt. */
    private static final int BLOCK = 1024 * 1024;

    /** Read size of a forward scan inside one checkpoint's span. */
    private static final int LOOKUP_BUFFER = 64 * 1024;

    private final Path file;
    private final long indexedBytes;
    private final int lineCount;
    private final long[] checkpointLines;
    private final long[] checkpointOffsets;

    private TerminalLogLineIndex(Path file, long indexedBytes, int lineCount,
                                 long[] checkpointLines, long[] checkpointOffsets) {
        this.file = file;
        this.indexedBytes = indexedBytes;
        this.lineCount = lineCount;
        this.checkpointLines = checkpointLines;
        this.checkpointOffsets = checkpointOffsets;
    }

    /**
     * Scans the first {@code lengthLimit} bytes of {@code file} (or the whole file when it is shorter)
     * and records where its lines start.
     *
     * <p>The limit is what makes a live log indexable: the viewer indexes what existed when it was
     * opened, and anything appended later is simply not part of this index.</p>
     *
     * @param lengthLimit how many bytes to index at most
     * @param bytesScanned receives the number of bytes scanned so far, after each block; may be {@code null}
     * @param cancelled polled before each block; may be {@code null} for never
     * @throws TerminalLogScratch.CancelledException when {@code cancelled} reports true
     * @throws IOException when the file cannot be read, shrinks while it is scanned, or has more lines
     *                     than an index can count
     */
    public static TerminalLogLineIndex build(Path file, long lengthLimit, LongConsumer bytesScanned,
                                             BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long limit = Math.min(Math.max(0L, lengthLimit), channel.size());
            byte[] block = new byte[(int) Math.min(BLOCK, Math.max(1L, limit))];
            ByteBuffer buffer = ByteBuffer.wrap(block);
            LongList lines = new LongList();
            LongList offsets = new LongList();
            lines.add(0L);
            offsets.add(0L);
            long position = 0;
            long newlines = 0;
            long lineStart = 0;
            long lastCheckpointLine = 0;
            long lastCheckpointOffset = 0;
            while (position < limit) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new TerminalLogScratch.CancelledException("Indexing " + file.getFileName() + " was cancelled");
                }
                int wanted = (int) Math.min(block.length, limit - position);
                int got = readBlock(channel, buffer, wanted, position);
                if (got < wanted) {
                    throw new IOException("The log file " + file.getFileName() + " shrank while it was indexed");
                }
                for (int i = 0; i < got; i++) {
                    if (block[i] == '\n') {
                        newlines++;
                        long next = position + i + 1;
                        lineStart = next;
                        if (newlines - lastCheckpointLine >= CHECKPOINT_LINES
                                || next - lastCheckpointOffset >= CHECKPOINT_BYTES) {
                            lines.add(newlines);
                            offsets.add(next);
                            lastCheckpointLine = newlines;
                            lastCheckpointOffset = next;
                        }
                    }
                }
                if (newlines >= Integer.MAX_VALUE - 1L) {
                    throw new IOException("The log file " + file.getFileName() + " has too many lines to index");
                }
                position += got;
                if (bytesScanned != null) {
                    bytesScanned.accept(position);
                }
            }
            // Bytes after the last LF are a line of their own, which the file ends without terminating.
            long count = newlines + (limit > lineStart ? 1 : 0);
            if (count > Integer.MAX_VALUE) {
                throw new IOException("The log file " + file.getFileName() + " has too many lines to index");
            }
            return new TerminalLogLineIndex(file.toAbsolutePath().normalize(), limit, (int) count,
                lines.toArray(), offsets.toArray());
        }
    }

    /** The number of lines in the indexed range: every LF, plus one for bytes after the last LF. */
    public int lineCount() {
        return lineCount;
    }

    /** How many bytes of the file the index covers, from the start of the file. */
    public long indexedBytes() {
        return indexedBytes;
    }

    /** How many checkpoints were recorded; a measure of the index's size, always at least one. */
    public int checkpointCount() {
        return checkpointLines.length;
    }

    /** The file this index was built from. */
    public Path file() {
        return file;
    }

    /**
     * The byte offset at which {@code line} (0-based) starts.
     *
     * <p>A binary search finds the nearest checkpoint at or before the line; the scan forward from
     * there is bounded by that checkpoint's span, which is at most about 1 MiB plus one line.</p>
     *
     * @throws IllegalArgumentException when {@code line} is outside {@code [0, lineCount())}
     * @throws IOException when the file cannot be read, or changed since the index was built
     */
    public long offsetOf(int line) throws IOException {
        checkLine(line);
        int checkpoint = checkpointIndexAtOrBefore(line);
        if (line == checkpointLines[checkpoint]) {
            return checkpointOffsets[checkpoint];
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return offsetOf(channel, line);
        }
    }

    /**
     * The same lookup as {@link #offsetOf(int)}, reading through a channel the caller holds. A reader
     * that keeps its channel open uses this, so a file that was since deleted (but is still open on
     * a system that allows it) keeps yielding lines instead of failing at the path lookup.
     */
    long offsetOf(FileChannel channel, int line) throws IOException {
        checkLine(line);
        int checkpoint = checkpointIndexAtOrBefore(line);
        long toSkip = line - checkpointLines[checkpoint];
        long position = checkpointOffsets[checkpoint];
        if (toSkip == 0) {
            return position;
        }
        byte[] chunk = new byte[LOOKUP_BUFFER];
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        while (position < indexedBytes) {
            int wanted = (int) Math.min(chunk.length, indexedBytes - position);
            int got = readBlock(channel, buffer, wanted, position);
            for (int i = 0; i < got; i++) {
                if (chunk[i] == '\n' && --toSkip == 0) {
                    return position + i + 1;
                }
            }
            position += got;
            if (got < wanted) {
                break;
            }
        }
        throw new IOException("The log file " + file.getFileName() + " changed while its line index was in use");
    }

    private void checkLine(int line) {
        if (line < 0 || line >= lineCount) {
            throw new IllegalArgumentException("Line " + line + " is outside 0.." + (lineCount - 1));
        }
    }

    /** The index of the last checkpoint whose line is at most {@code line}; checkpoint 0 is line 0. */
    int checkpointIndexAtOrBefore(int line) {
        int low = 0;
        int high = checkpointLines.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (checkpointLines[middle] <= line) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** The first line of checkpoint {@code checkpoint}. */
    long checkpointLine(int checkpoint) {
        return checkpointLines[checkpoint];
    }

    /** The byte offset of checkpoint {@code checkpoint}, which is always the start of a line. */
    long checkpointOffset(int checkpoint) {
        return checkpointOffsets[checkpoint];
    }

    /** Reads up to {@code wanted} bytes at {@code position} into the buffer; returns how many came. */
    private static int readBlock(FileChannel channel, ByteBuffer buffer, int wanted, long position) throws IOException {
        buffer.clear();
        buffer.limit(wanted);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.position();
    }

    /** A growable list of longs, so the checkpoints need no boxing. */
    private static final class LongList {

        private long[] values = new long[64];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
