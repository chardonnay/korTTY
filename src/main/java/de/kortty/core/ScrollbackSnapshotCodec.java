package de.kortty.core;

import de.kortty.security.EncryptionService;
import org.jetbrains.annotations.Nullable;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The content of one saved scrollback file ({@link SessionScrollbackStore}): the last rows of one
 * terminal pane, encrypted with the key derived from the master password.
 *
 * <p>The plain text is a header line, the time it was saved and the rows, one per line. It is
 * encrypted as a whole with AES-256-GCM ({@link EncryptionService#encrypt}), so a file read with
 * another key (after the master password changed) or a file someone edited does not decode at all:
 * GCM authenticates the content. The file is the prefix {@value #PREFIX} and the Base64 text.
 *
 * <p>No key, no file: {@link #encode} refuses to run without one, it never writes plain text.
 *
 * <p>The rows are bounded in both directions: at most {@link #MAX_LINES} rows of at most
 * {@link #MAX_LINE_LENGTH} characters are encoded, and {@link #decode} keeps no more than that from
 * a file, whatever it holds. Toolkit-free.
 */
public final class ScrollbackSnapshotCodec {

    /** The fewest rows per pane the setting allows. */
    public static final int MIN_LINES = 100;

    /** The rows per pane when the setting was never changed. */
    public static final int DEFAULT_LINES = 1000;

    /** The most rows per pane the setting allows, and the most any file decodes to. */
    public static final int MAX_LINES = 5000;

    /** The longest row kept; a longer one is cut. Rows are terminal rows, or a few joined ones. */
    static final int MAX_LINE_LENGTH = 16_384;

    /** The start of every scrollback file: the format and its version. */
    static final String PREFIX = "KSB1:";

    private static final String HEADER = "korTTY scrollback 1";

    private static final String SAVED_AT = "savedAt=";

    private final EncryptionService encryption;

    /** What one file holds: the rows, oldest first, and when they were saved (epoch millis). */
    public record Decoded(List<String> lines, long savedAtMillis) {
        public Decoded {
            lines = List.copyOf(lines);
        }
    }

    public ScrollbackSnapshotCodec() {
        this(new EncryptionService());
    }

    ScrollbackSnapshotCodec(EncryptionService encryption) {
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    /** {@code requested} rows per pane within {@link #MIN_LINES} to {@link #MAX_LINES}. */
    public static int clampLines(int requested) {
        return Math.max(MIN_LINES, Math.min(MAX_LINES, requested));
    }

    /**
     * Encrypts the newest {@code maxLines} of {@code lines} with {@code key}.
     *
     * @param maxLines the rows to keep, clamped to {@link #MIN_LINES}..{@link #MAX_LINES}
     * @throws IllegalStateException without a key: nothing is ever stored unencrypted
     * @throws IOException when the encryption fails
     */
    public String encode(List<String> lines, long savedAtMillis, int maxLines, @Nullable SecretKey key)
            throws IOException {
        if (key == null) {
            throw new IllegalStateException("No master-password key: scrollback is never stored unencrypted");
        }
        List<String> kept = newest(lines != null ? lines : List.of(), clampLines(maxLines));
        StringBuilder plain = new StringBuilder(64 + kept.size() * 40);
        plain.append(HEADER).append('\n').append(SAVED_AT).append(savedAtMillis).append('\n');
        for (String line : kept) {
            plain.append(clean(line)).append('\n');
        }
        try {
            return PREFIX + encryption.encrypt(plain.toString(), key);
        } catch (Exception e) {
            throw new IOException("Could not encrypt the scrollback", e);
        }
    }

    /**
     * Decrypts a file {@link #encode} wrote.
     *
     * @throws IOException when the content is not a scrollback file, or does not decrypt with
     *     {@code key} (another master password, an edited file)
     * @throws IllegalStateException without a key
     */
    public Decoded decode(String content, @Nullable SecretKey key) throws IOException {
        if (key == null) {
            throw new IllegalStateException("No master-password key: the scrollback cannot be read");
        }
        if (content == null || !content.startsWith(PREFIX)) {
            throw new IOException("Not a korTTY scrollback file");
        }
        String plain;
        try {
            plain = encryption.decrypt(content.substring(PREFIX.length()).trim(), key);
        } catch (Exception e) {
            throw new IOException("The scrollback does not decrypt with the current master-password key", e);
        }
        String[] rows = plain.split("\n", -1);
        if (rows.length < 2 || !HEADER.equals(rows[0]) || !rows[1].startsWith(SAVED_AT)) {
            throw new IOException("Not a korTTY scrollback file");
        }
        long savedAt;
        try {
            savedAt = Long.parseLong(rows[1].substring(SAVED_AT.length()));
        } catch (NumberFormatException e) {
            throw new IOException("Not a korTTY scrollback file", e);
        }
        // Every row ends with a line break, so the text ends with an empty last element.
        int end = rows.length > 2 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length;
        int start = Math.max(2, end - MAX_LINES);
        List<String> lines = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            lines.add(clean(rows[i]));
        }
        return new Decoded(lines, savedAt);
    }

    private static List<String> newest(List<String> lines, int maxLines) {
        int from = Math.max(0, lines.size() - maxLines);
        return lines.subList(from, lines.size());
    }

    /** One row as stored: no line break inside it (it would split the row), no longer than the limit. */
    private static String clean(@Nullable String line) {
        if (line == null) {
            return "";
        }
        String single = line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0
            ? line.replace('\r', ' ').replace('\n', ' ')
            : line;
        return single.length() > MAX_LINE_LENGTH ? single.substring(0, MAX_LINE_LENGTH) : single;
    }
}
