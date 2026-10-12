package de.kortty.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads pages of lines out of a log file, using a {@link TerminalLogLineIndex} to find where they start.
 *
 * <p>A page is one positional read sequence: the index gives the offset of the first line, then
 * blocks of up to 1 MiB are read and split at line feeds until the requested lines are complete. A
 * line longer than {@value #MAX_LINE_BYTES} bytes is cut for display and flagged, since a terminal
 * line of that size is almost always a binary dump or a runaway program, and holding it whole in
 * the viewer serves nobody. Its text is capped again at {@value #DISPLAY_CHARS} characters for the
 * same reason. The rest of such a line is still skipped correctly, so the line numbers that follow
 * stay right.</p>
 *
 * <p>The reader can keep one channel open for its life, which suits a decompressed copy that
 * nothing else will touch. For a live original it opens the file for each page instead: on Windows
 * an open file cannot be deleted or renamed, and the logger must be able to compress the file it
 * just closed. Such a reader notices a rotated or compressed file as {@link RotatedException} rather
 * than returning lines from the wrong file.</p>
 *
 * <p>All public methods are synchronized, so a page and a close never interleave.</p>
 */
public final class TerminalLogReader implements AutoCloseable {

    /** The most of a line that is decoded; the rest is skipped. */
    public static final int MAX_LINE_BYTES = 64 * 1024;

    /** The most characters of a line that are shown. */
    public static final int DISPLAY_CHARS = 8192;

    /** Bytes read per positional read. */
    private static final int BLOCK = 1024 * 1024;

    /**
     * One line of a page.
     *
     * @param number the 0-based line number in the file
     * @param text the line without its line feed and without a trailing carriage return, decoded as UTF-8
     *             with malformed bytes replaced
     * @param truncated whether the line was cut for display, by {@link #MAX_LINE_BYTES} or {@link #DISPLAY_CHARS}
     */
    public record Line(int number, String text, boolean truncated) {
    }

    /**
     * The file changed under the index: it was rotated, compressed or deleted, or it is shorter than
     * the index says. The caller should rebuild the index, or reopen the file, rather than retry.
     */
    public static final class RotatedException extends IOException {

        private static final long serialVersionUID = 1L;

        public RotatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path file;
    private final TerminalLogLineIndex index;
    private final FileChannel channel;
    private boolean closed;

    private TerminalLogReader(Path file, TerminalLogLineIndex index, FileChannel channel) {
        this.file = file;
        this.index = index;
        this.channel = channel;
    }

    /**
     * Opens a reader over {@code file}, whose lines {@code index} describes.
     *
     * @param keepChannelOpen {@code true} to hold one channel for the reader's life (a decompressed
     *                        copy); {@code false} to open the file for each page (a live original,
     *                        which must stay deletable)
     * @throws IOException when {@code keepChannelOpen} is set and the file cannot be opened
     */
    public static TerminalLogReader open(Path file, TerminalLogLineIndex index, boolean keepChannelOpen)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(index, "index");
        FileChannel channel = keepChannelOpen ? FileChannel.open(file, StandardOpenOption.READ) : null;
        return new TerminalLogReader(file, index, channel);
    }

    /**
     * Reads up to {@code count} lines starting at {@code fromLine}.
     *
     * <p>Fewer lines come back when the page runs past the end of the indexed range; a line past the
     * end yields an empty list rather than an error, because the file may have grown since the page
     * was asked for.</p>
     *
     * @throws IllegalArgumentException when {@code fromLine} is negative
     * @throws RotatedException when the file was rotated, compressed or deleted since the index was built
     * @throws IOException when the file cannot be read or the reader is closed
     */
    public synchronized List<Line> readPage(int fromLine, int count) throws IOException {
        ensureOpen();
        if (fromLine < 0) {
            throw new IllegalArgumentException("fromLine is negative: " + fromLine);
        }
        if (count <= 0 || fromLine >= index.lineCount()) {
            return List.of();
        }
        if (channel != null) {
            return readWith(channel, fromLine, count);
        }
        try (FileChannel perPage = FileChannel.open(file, StandardOpenOption.READ)) {
            return readWith(perPage, fromLine, count);
        } catch (NoSuchFileException e) {
            throw rotated(e);
        }
    }

    /** The index this reader pages through. */
    public synchronized TerminalLogLineIndex index() {
        return index;
    }

    /** Closes the held channel, if any; closing twice is harmless. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (channel != null) {
            channel.close();
        }
    }

    /** The file being read; the search opens its own channel on it. */
    Path file() {
        return file;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The log reader for " + file.getFileName() + " is closed");
        }
    }

    private RotatedException rotated(NoSuchFileException cause) {
        return new RotatedException("The log file " + file.getFileName()
            + " was rotated, compressed or deleted while it was open", cause);
    }

    /** One page through {@code source}: the offset lookup and the reads share the same channel. */
    private List<Line> readWith(FileChannel source, int fromLine, int count) throws IOException {
        long start = index.offsetOf(source, fromLine);
        return readFrom(source, fromLine, count, start, index.indexedBytes());
    }

    private List<Line> readFrom(FileChannel source, int fromLine, int count, long start, long end)
            throws IOException {
        Assembler assembler = new Assembler(fromLine, count);
        byte[] block = new byte[(int) Math.max(1L, Math.min(BLOCK, end - start))];
        long position = start;
        while (position < end && !assembler.full()) {
            int wanted = (int) Math.min(block.length, end - position);
            int got = readFully(source, block, wanted, position);
            if (got < wanted) {
                throw new RotatedException("The log file " + file.getFileName()
                    + " is shorter than its index; it was rotated or compressed", null);
            }
            assembler.feed(block, got);
            position += got;
        }
        if (!assembler.full()) {
            // The indexed range ended mid-line: that last line has no LF, but it is still a line.
            assembler.finish();
        }
        return assembler.lines;
    }

    /** Reads exactly {@code wanted} bytes at {@code position} into {@code target}, or fewer at end of file. */
    private static int readFully(FileChannel source, byte[] target, int wanted, long position) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(target, 0, wanted);
        while (buffer.hasRemaining()) {
            if (source.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.position();
    }

    /**
     * Splits the bytes of a page into lines. It keeps at most one byte more than a line may hold, so
     * that a trailing carriage return can be told apart from a cut-off line, and counts the rest.
     */
    private static final class Assembler {

        private final int firstLine;
        private final int count;
        private final List<Line> lines;
        private final byte[] current = new byte[MAX_LINE_BYTES + 1];
        private int rawLength;

        Assembler(int firstLine, int count) {
            this.firstLine = firstLine;
            this.count = count;
            this.lines = new ArrayList<>(Math.min(count, 256));
        }

        boolean full() {
            return lines.size() >= count;
        }

        void feed(byte[] data, int length) {
            for (int i = 0; i < length && !full(); i++) {
                byte b = data[i];
                if (b == '\n') {
                    emit();
                } else {
                    if (rawLength < current.length) {
                        current[rawLength] = b;
                    }
                    if (rawLength < Integer.MAX_VALUE) {
                        rawLength++;
                    }
                }
            }
        }

        /** Emits the bytes after the last LF, if there are any. */
        void finish() {
            if (rawLength > 0) {
                emit();
            }
        }

        private void emit() {
            int content = rawLength;
            if (rawLength <= current.length && rawLength > 0 && current[rawLength - 1] == '\r') {
                content = rawLength - 1;
            }
            boolean truncated = content > MAX_LINE_BYTES;
            String text = new String(current, 0, Math.min(content, MAX_LINE_BYTES), StandardCharsets.UTF_8);
            if (text.length() > DISPLAY_CHARS) {
                int cut = DISPLAY_CHARS;
                if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                    cut--; // never split a surrogate pair
                }
                text = text.substring(0, cut);
                truncated = true;
            }
            lines.add(new Line(firstLine + lines.size(), text, truncated));
            rawLength = 0;
        }
    }
}
