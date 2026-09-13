package de.kortty.control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded newline-delimited UTF-8 line reader and writer.
 *
 * <p>Never allocates more than {@code maxLineBytes} for one inbound line: the cap is enforced while
 * the bytes are being accumulated, not afterwards, so an unterminated multi-gigabyte line cannot
 * exhaust the heap before it is rejected. {@link #writeLine(String)} performs exactly one
 * {@code write} plus one {@code flush} on the raw stream, which is what keeps a frame indivisible on
 * the wire.
 *
 * <p>Any thread, but a single instance is owned by one reader thread and one writer thread; it is not
 * safe for two threads to read, or for two threads to write, concurrently.
 */
public final class ControlLineCodec {

    private static final int LF = '\n';

    private static final int CR = '\r';

    private final InputStream in;

    private final OutputStream out;

    private final int maxLineBytes;

    /**
     * @param in the inbound stream; wrapped in a {@link BufferedInputStream} when it is not already
     *     buffered, because the reader consumes one byte at a time
     * @param out the outbound stream, used raw so one line is one write
     * @param maxLineBytes the largest accepted line payload, excluding the terminating newline
     */
    public ControlLineCodec(InputStream in, OutputStream out, int maxLineBytes) {
        Objects.requireNonNull(in, "in");
        this.in = in instanceof BufferedInputStream buffered ? buffered : new BufferedInputStream(in, 8192);
        this.out = Objects.requireNonNull(out, "out");
        if (maxLineBytes <= 0) {
            throw new IllegalArgumentException("maxLineBytes must be positive: " + maxLineBytes);
        }
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * Reads one line, tolerating a trailing CR.
     *
     * @return the decoded line, or null at end of stream with nothing buffered
     * @throws ControlApiException {@link ControlErrorCode#MESSAGE_TOO_LARGE} once the payload exceeds
     *     the cap; the caller must close the connection, because a truncated line cannot be
     *     resynchronised safely
     */
    public String readLine() throws IOException, ControlApiException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int read;
        while ((read = in.read()) >= 0) {
            if (read == LF) {
                return decode(buffer);
            }
            if (buffer.size() >= maxLineBytes) {
                throw new ControlApiException(ControlErrorCode.MESSAGE_TOO_LARGE,
                    "Request line exceeds " + maxLineBytes + " bytes",
                    Map.of("max_line_bytes", maxLineBytes));
            }
            buffer.write(read);
        }
        return buffer.size() == 0 ? null : decode(buffer);
    }

    /** Writes {@code line} plus a newline in one write, then flushes. */
    public void writeLine(String line) throws IOException {
        String text = line == null ? "" : line;
        if (!text.endsWith("\n")) {
            text = text + "\n";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.flush();
    }

    private static String decode(ByteArrayOutputStream buffer) {
        byte[] bytes = buffer.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == CR) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
