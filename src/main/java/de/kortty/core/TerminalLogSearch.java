package de.kortty.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

/**
 * Finds a text in a log file, line by line, without loading the file.
 *
 * <p>Matching is case-insensitive the way {@link String#regionMatches(boolean, int, String, int, int)}
 * compares, one character against one character. Lowercasing the whole text would be wrong: some
 * characters change length when case is changed, which would shift every position after them.</p>
 *
 * <p>The search streams the file one checkpoint block at a time, since a block ends on a line
 * boundary: no line, and so no match, can straddle two blocks. Within a block the text is decoded
 * in chunks, and each chunk keeps the last {@code needle.length() - 1} characters for the next one,
 * so a match across a chunk boundary is found exactly once. A match is attributed to the line it
 * starts on, which is what the viewer scrolls to.</p>
 *
 * <p>A cancelled search returns nothing rather than throwing: the caller asked for it, and a partial
 * answer would look like a complete one.</p>
 */
public final class TerminalLogSearch {

    /**
     * The count's default ceiling. A needle as common as {@code e} would otherwise walk a whole
     * gigabyte to report a number nobody reads.
     */
    public static final int DEFAULT_CAP = 10_000;

    /** Characters decoded per read. Large enough that the per-chunk work is cheap, small enough to cancel promptly. */
    private static final int CHUNK_CHARS = 64 * 1024;

    private TerminalLogSearch() {
    }

    /**
     * The first line at or after {@code fromLine} that contains {@code needle}.
     *
     * @param needle non-empty, without a line feed
     * @param fromLine the first line to look at, inclusive
     * @return the line, or empty when there is none or the search was cancelled
     * @throws IllegalArgumentException when the needle is empty or contains a line feed
     * @throws IOException when the file cannot be read
     */
    public static OptionalInt findForward(TerminalLogReader reader, String needle, int fromLine,
                                          BooleanSupplier cancelled) throws IOException {
        requireNeedle(needle);
        TerminalLogLineIndex index = reader.index();
        int from = Math.max(0, fromLine);
        if (from >= index.lineCount()) {
            return OptionalInt.empty();
        }
        int[] found = {-1};
        try (FileChannel channel = openChannel(reader)) {
            for (int block = index.checkpointIndexAtOrBefore(from); block < index.checkpointCount(); block++) {
                scanBlock(channel, index, block, needle, cancelled, null, line -> {
                    if (line < from) {
                        return true;
                    }
                    found[0] = line;
                    return false;
                });
                if (found[0] >= 0) {
                    return OptionalInt.of(found[0]);
                }
                if (isCancelled(cancelled)) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The last line at or before {@code fromLine} that contains {@code needle}.
     *
     * <p>Blocks are searched from the one holding {@code fromLine} back towards the start, and the
     * search stops at the first block with a hit, because every earlier block is entirely before it.</p>
     *
     * @param fromLine the last line to look at, inclusive; a value past the end starts at the last line
     * @return the line, or empty when there is none or the search was cancelled
     * @throws IllegalArgumentException when the needle is empty or contains a line feed
     * @throws IOException when the file cannot be read
     */
    public static OptionalInt findBackward(TerminalLogReader reader, String needle, int fromLine,
                                           BooleanSupplier cancelled) throws IOException {
        requireNeedle(needle);
        TerminalLogLineIndex index = reader.index();
        int lines = index.lineCount();
        if (lines == 0) {
            return OptionalInt.empty();
        }
        int from = Math.min(fromLine, lines - 1);
        if (from < 0) {
            return OptionalInt.empty();
        }
        try (FileChannel channel = openChannel(reader)) {
            for (int block = index.checkpointIndexAtOrBefore(from); block >= 0; block--) {
                int[] last = {-1};
                scanBlock(channel, index, block, needle, cancelled, null, line -> {
                    if (line <= from) {
                        last[0] = line;
                    }
                    return true;
                });
                if (last[0] >= 0) {
                    return OptionalInt.of(last[0]);
                }
                if (isCancelled(cancelled)) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * How many places in the file {@code needle} occurs, counting every starting position (so
     * {@code aa} occurs twice in {@code aaa}, as the viewer's find highlights it), up to {@code cap}.
     *
     * @param cap the most that is counted; reaching it ends the search early
     * @param percent receives the progress from 0 to 100, at most once per change, ending with 100;
     *                may be {@code null}
     * @return the count, at most {@code cap}; 0 when the search was cancelled
     * @throws IllegalArgumentException when the needle is empty or contains a line feed
     * @throws IOException when the file cannot be read
     */
    public static int countMatches(TerminalLogReader reader, String needle, int cap, BooleanSupplier cancelled,
                                   IntConsumer percent) throws IOException {
        requireNeedle(needle);
        if (cap <= 0) {
            return 0;
        }
        TerminalLogLineIndex index = reader.index();
        long total = index.indexedBytes();
        int[] count = {0};
        int[] reported = {-1};
        LongConsumer progress = percent == null || total <= 0 ? null : position -> {
            int value = (int) Math.min(100L, position * 100L / total);
            if (value != reported[0]) {
                reported[0] = value;
                percent.accept(value);
            }
        };
        try (FileChannel channel = openChannel(reader)) {
            for (int block = 0; block < index.checkpointCount(); block++) {
                scanBlock(channel, index, block, needle, cancelled, progress, line -> {
                    count[0]++;
                    return count[0] < cap;
                });
                if (count[0] >= cap) {
                    return cap;
                }
                if (isCancelled(cancelled)) {
                    return 0;
                }
            }
        }
        if (percent != null) {
            percent.accept(100);
        }
        return count[0];
    }

    /**
     * The column at which {@code needle} first occurs in one line, compared without regard to case,
     * or -1. The column counts characters, which is what the viewer's text positions use.
     */
    public static int columnOf(String lineText, String needle) {
        if (lineText == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        return indexOfIgnoreCase(lineText, needle, 0);
    }

    /**
     * The first column at or after {@code from} where {@code needle} occurs, compared without regard
     * to case, or -1. Shared with the line styler, so the find highlights and the find-next jump agree.
     */
    static int indexOfIgnoreCase(String text, String needle, int from) {
        int last = text.length() - needle.length();
        for (int at = Math.max(0, from); at <= last; at++) {
            if (text.regionMatches(true, at, needle, 0, needle.length())) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Streams the lines of checkpoint block {@code block} and reports the line of each match to
     * {@code onMatch}, in file order, until that returns false.
     *
     * @return {@code true} when the whole block was scanned; {@code false} when {@code onMatch}
     *         stopped it or the search was cancelled
     */
    private static boolean scanBlock(FileChannel channel, TerminalLogLineIndex index, int block, String needle,
                                     BooleanSupplier cancelled, LongConsumer progress, IntPredicate onMatch)
            throws IOException {
        long start = index.checkpointOffset(block);
        long end = block + 1 < index.checkpointCount() ? index.checkpointOffset(block + 1) : index.indexedBytes();
        if (end <= start) {
            return true;
        }
        int length = needle.length();
        int overlap = length - 1;
        int lineAtStart = (int) index.checkpointLine(block);
        Window window = new Window(channel, index.file(), start, end);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // A block is usually far smaller than a chunk, and a file has thousands of blocks: the buffer
        // is sized to the block, which can never decode to more characters than it has bytes.
        char[] chars = new char[(int) Math.min(CHUNK_CHARS, end - start) + overlap];
        int kept = 0;
        try (Reader in = Channels.newReader(window, decoder, -1)) {
            while (true) {
                if (isCancelled(cancelled)) {
                    return false;
                }
                int read = in.read(chars, kept, chars.length - kept);
                if (read < 0) {
                    return true;
                }
                if (progress != null) {
                    progress.accept(window.position());
                }
                int total = kept + read;
                String text = new String(chars, 0, total);
                int line = lineAtStart;
                int counted = 0;
                // A start is examined only in the chunk where its whole needle is present, so a
                // match near the end of this chunk waits for the next one.
                for (int at = 0; at + length <= total; at++) {
                    if (text.regionMatches(true, at, needle, 0, length)) {
                        for (; counted < at; counted++) {
                            if (chars[counted] == '\n') {
                                line++;
                            }
                        }
                        if (!onMatch.test(line)) {
                            return false;
                        }
                    }
                }
                // Keep the last overlap characters: they start a match the next chunk completes.
                int keep = Math.min(overlap, total);
                int consumed = total - keep;
                for (int i = 0; i < consumed; i++) {
                    if (chars[i] == '\n') {
                        lineAtStart++;
                    }
                }
                System.arraycopy(chars, consumed, chars, 0, keep);
                kept = keep;
            }
        }
    }

    private static FileChannel openChannel(TerminalLogReader reader) throws IOException {
        try {
            return FileChannel.open(reader.file(), StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw new TerminalLogReader.RotatedException("The log file " + reader.file().getFileName()
                + " was rotated, compressed or deleted while it was open", e);
        }
    }

    private static void requireNeedle(String needle) {
        if (needle == null || needle.isEmpty()) {
            throw new IllegalArgumentException("The search text must not be empty");
        }
        if (needle.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("The search text must not span lines");
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    /** The bytes of one checkpoint block, as a channel that ends at the block's end. */
    private static final class Window implements ReadableByteChannel {

        private final FileChannel channel;
        private final Path file;
        private final long end;
        private long at;
        private boolean open = true;

        Window(FileChannel channel, Path file, long start, long end) {
            this.channel = channel;
            this.file = file;
            this.at = start;
            this.end = end;
        }

        /** The absolute byte offset read up to. */
        long position() {
            return at;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            long remaining = end - at;
            if (remaining <= 0) {
                return -1;
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            int limit = target.limit();
            if (target.remaining() > remaining) {
                target.limit(target.position() + (int) remaining);
            }
            int read;
            try {
                read = channel.read(target, at);
            } finally {
                target.limit(limit);
            }
            if (read < 0) {
                // The index promised more bytes than the file has: it was rotated or truncated,
                // which the reader reports the same way — not as "no match".
                throw new TerminalLogReader.RotatedException("The log file " + file.getFileName()
                    + " is shorter than its index; it was rotated or compressed", null);
            }
            if (read > 0) {
                at += read;
            }
            return read;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        /** Closes the window only; the channel belongs to the caller. */
        @Override
        public void close() {
            open = false;
        }
    }
}
