package de.kortty.shellintegration;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The rules for {@code OSC 52}, with which a program puts text on the terminal's clipboard: vim,
 * Neovim and tmux use it to copy on a server into the clipboard of the computer in front of you.
 *
 * <ul>
 *   <li>The selection parameter {@code Pc} may be empty or any of {@code c} (clipboard), {@code p}
 *       (primary), {@code q} (secondary), {@code s} (select) and the cut buffers {@code 0-7}; all of
 *       them mean the one clipboard korTTY writes. Anything else is no clipboard write.</li>
 *   <li>A query ({@code ?} instead of data) is never answered: korTTY never hands the clipboard to
 *       a program. An empty write, which clears the selection in xterm, is ignored.</li>
 *   <li>The data is base64. Line breaks and blanks are removed first, since {@code base64} without
 *       {@code -w0} breaks its output every 76 chars; what remains must be strict base64 of valid
 *       UTF-8 text of at most {@link #MAX_DECODED_BYTES} bytes, or nothing is written.</li>
 * </ul>
 *
 * <p>FX-free and stateless; any thread.
 */
public final class Osc52Support {

    /** The most bytes of text one write may put on the clipboard: 256 KiB. */
    public static final int MAX_DECODED_BYTES = 256 * 1024;

    /** The base64 length of {@link #MAX_DECODED_BYTES}, without line breaks. */
    static final int MAX_ENCODED_CHARS = (MAX_DECODED_BYTES + 2) / 3 * 4;

    /** Why {@link #decode} wrote nothing. */
    public enum Rejection {
        /** No data, or nothing but blanks and line breaks; not worth a message. */
        EMPTY,
        /** Not base64. */
        NOT_BASE64,
        /** More than {@link #MAX_DECODED_BYTES} bytes once decoded. */
        TOO_LARGE,
        /** Decodes to bytes that are not UTF-8 text. */
        NOT_UTF8
    }

    /**
     * The text of a write, or why there is none.
     *
     * @param text      the decoded text, never empty; {@code null} when rejected
     * @param bytes     how many UTF-8 bytes {@code text} has; 0 when rejected
     * @param rejection why there is no text; {@code null} when there is
     */
    public record Decoded(@Nullable String text, int bytes, @Nullable Rejection rejection) {

        public Decoded {
            if ((text == null) == (rejection == null)) {
                throw new IllegalArgumentException("either text or a rejection");
            }
        }

        static Decoded of(String text, int bytes) {
            return new Decoded(text, bytes, null);
        }

        static Decoded rejected(Rejection rejection) {
            return new Decoded(null, 0, rejection);
        }
    }

    private Osc52Support() {
    }

    /**
     * Reads the payload of an {@code OSC 52} sequence, the body after {@code 52;} without its
     * terminator.
     *
     * @return the write, or {@code null} for a query, an empty write and a selection parameter that
     *         is none of xterm's
     */
    static @Nullable ShellIntegrationEvent.ClipboardWrite parse(String payload) {
        int separator = payload.indexOf(';');
        if (separator < 0) {
            return null;
        }
        String selection = payload.substring(0, separator);
        String data = payload.substring(separator + 1);
        if (!isSelection(selection) || data.isEmpty() || data.equals("?")) {
            return null;
        }
        return new ShellIntegrationEvent.ClipboardWrite(selection, data);
    }

    /** Whether {@code selection} is a {@code Pc} parameter xterm knows: empty, or made of {@code c p q s 0-7}. */
    public static boolean isSelection(String selection) {
        for (int i = 0; i < selection.length(); i++) {
            char c = selection.charAt(i);
            if (c != 'c' && c != 'p' && c != 'q' && c != 's' && (c < '0' || c > '7')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the data of a write: removes blanks and line breaks, then reads strict base64 and
     * strict UTF-8, at most {@link #MAX_DECODED_BYTES} bytes.
     */
    public static Decoded decode(String data) {
        Objects.requireNonNull(data, "data");
        StringBuilder base64 = new StringBuilder(Math.min(data.length(), MAX_ENCODED_CHARS));
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t' || c == '\f' || c == '\u000B') {
                continue;
            }
            if (base64.length() == MAX_ENCODED_CHARS) {
                // Longer than the base64 of the cap, padding included: more than the cap, or no base64.
                return Decoded.rejected(Rejection.TOO_LARGE);
            }
            base64.append(c);
        }
        if (base64.isEmpty()) {
            return Decoded.rejected(Rejection.EMPTY);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.toString());
        } catch (IllegalArgumentException notBase64) {
            return Decoded.rejected(Rejection.NOT_BASE64);
        }
        if (bytes.length > MAX_DECODED_BYTES) {
            return Decoded.rejected(Rejection.TOO_LARGE);
        }
        if (bytes.length == 0) {
            return Decoded.rejected(Rejection.EMPTY);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            return Decoded.of(text, bytes.length);
        } catch (CharacterCodingException notUtf8) {
            return Decoded.rejected(Rejection.NOT_UTF8);
        }
    }
}
