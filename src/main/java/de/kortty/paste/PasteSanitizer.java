package de.kortty.paste;

import java.nio.charset.Charset;

/**
 * Removes the DECSET 2004 bracketed-paste markers from text that korTTY pastes into a pane.
 *
 * <p>A pasted {@code ESC [ 2 0 1 ~} ends the paste early: everything after it reaches the program as
 * typed input, so a line break in the rest submits a command the user never confirmed. The start
 * marker has no business inside a paste either. Both are removed in their 7-bit form
 * ({@code ESC [ 2 0 0 ~} / {@code ESC [ 2 0 1 ~}) and in their 8-bit C1 form
 * ({@code U+009B 2 0 0 ~} / {@code U+009B 2 0 1 ~}), which ISO-8859-1 and ISO-8859-15 send as the
 * CSI byte {@code 9B}. Windows-1252 sends {@code ›} (U+203A) as that byte instead, so text bound for a
 * known encoding goes through {@link #stripBracketMarkers(String, Charset)}, which also treats the
 * character that encoding writes as {@code 9B} as an 8-bit introducer.
 *
 * <p>The result equals removing markers again and again until none remain, so a marker that only
 * appears once an inner one is gone ({@code ESC [ 2 0 ESC [ 2 0 1 ~ 1 ~}) is removed as well. It is
 * computed in one pass with a constant look-back, because a crafted multi-megabyte paste of nested
 * half-markers would otherwise stall the thread doing the paste.
 *
 * <p>{@link #encode(String, boolean, Charset)} builds what a terminal paste sends: the text without
 * markers, its line breaks turned into the carriage return a pressed Enter produces, and the
 * markers korTTY adds itself when the program in the pane has enabled bracketed paste.
 *
 * <p>Pure, any thread.
 */
public final class PasteSanitizer {

    /** The DECSET 2004 start marker, {@code ESC [ 2 0 0 ~}. */
    public static final String START_MARKER = "\u001b[200~";

    /** The DECSET 2004 end marker, {@code ESC [ 2 0 1 ~}. */
    public static final String END_MARKER = "\u001b[201~";

    /** The 7-bit control sequence introducer's first character. */
    private static final char ESC = '\u001b';

    /** The 8-bit (C1) control sequence introducer. */
    private static final char CSI = '\u009b';

    /** The byte a single-byte encoding sends for the 8-bit control sequence introducer. */
    private static final byte CSI_BYTE = (byte) 0x9b;

    /** The length of {@code ESC [ 2 0 x ~}, the longest marker. */
    private static final int ESC_MARKER_LENGTH = 6;

    /** The length of {@code CSI 2 0 x ~}. */
    private static final int C1_MARKER_LENGTH = 5;

    private PasteSanitizer() {
    }

    /**
     * The text without any bracketed-paste start or end marker.
     *
     * @param text the text to paste
     * @return {@code text} itself when there was nothing to remove; "" for null
     */
    public static String stripBracketMarkers(String text) {
        return strip(text, CSI);
    }

    /**
     * {@link #stripBracketMarkers(String)} for text that is sent in {@code charset}: the character the
     * charset writes as the CSI byte {@code 9B} introduces an 8-bit marker as well, so a Windows-1252
     * pane also loses {@code › 2 0 1 ~}.
     *
     * @param text the text to paste
     * @param charset the encoding the text is sent in; null means UTF-8
     * @return {@code text} itself when there was nothing to remove; "" for null
     */
    public static String stripBracketMarkers(String text, Charset charset) {
        return strip(text, eightBitIntroducer(charset));
    }

    /**
     * The text with every line break as the carriage return Enter sends: {@code CR LF} and a lone
     * {@code LF} each become one {@code CR}, and a lone {@code CR} stays. Clipboards hold
     * {@code CR LF} on Windows and {@code LF} elsewhere, while a terminal program expects Enter.
     *
     * @param text the text to paste
     * @return {@code text} itself when it holds no {@code LF}; "" for null
     */
    public static String normaliseLineBreaks(String text) {
        if (text == null) {
            return "";
        }
        int first = text.indexOf('\n');
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n') {
                out.append(c);
            } else if (i == 0 || text.charAt(i - 1) != '\r') {
                // The LF of a CR LF pair adds nothing: its CR is already in the output.
                out.append('\r');
            }
        }
        return out.toString();
    }

    /**
     * {@link #encode(String, boolean, Charset)} for a pane that types text in UTF-8.
     */
    public static String encode(String text, boolean bracketed) {
        return encode(text, bracketed, null);
    }

    /**
     * What a terminal paste of {@code text} sends: the text without bracketed-paste markers (always,
     * so a paste can neither end the program's paste early nor fake one), its line breaks normalised
     * ({@link #normaliseLineBreaks}), and wrapped in {@link #START_MARKER} and {@link #END_MARKER}
     * when {@code bracketed}.
     *
     * @param text the text to paste
     * @param bracketed whether the program in the pane has enabled bracketed paste (DECSET 2004)
     * @param charset the encoding the pane sends text in, which decides the 8-bit marker form; null
     *     means UTF-8
     * @return the payload; "" for null or empty text, which is never wrapped
     */
    public static String encode(String text, boolean bracketed, Charset charset) {
        String payload = normaliseLineBreaks(stripBracketMarkers(text, charset));
        if (payload.isEmpty() || !bracketed) {
            return payload;
        }
        return START_MARKER + payload + END_MARKER;
    }

    /** The character {@code charset} writes as the single byte {@code 9B}, or {@link #CSI} for none. */
    static char eightBitIntroducer(Charset charset) {
        if (charset == null || !charset.canEncode()) {
            return CSI;
        }
        String decoded = new String(new byte[] {CSI_BYTE}, charset);
        if (decoded.length() != 1) {
            return CSI;
        }
        byte[] encoded = decoded.getBytes(charset);
        return encoded.length == 1 && encoded[0] == CSI_BYTE ? decoded.charAt(0) : CSI;
    }

    private static String strip(String text, char eightBitIntroducer) {
        if (text == null) {
            return "";
        }
        int first = firstIntroducer(text, eightBitIntroducer);
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (c == '~') {
                // The builder never holds a marker, so a new one can only end at the character just
                // appended; deleting it leaves a prefix of a marker-free string, which stays marker-free.
                int markerLength = markerLengthAtEnd(out, eightBitIntroducer);
                if (markerLength > 0) {
                    out.setLength(out.length() - markerLength);
                }
            }
        }
        return out.length() == text.length() ? text : out.toString();
    }

    private static int firstIntroducer(String text, char eightBitIntroducer) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ESC || c == CSI || c == eightBitIntroducer) {
                return i;
            }
        }
        return -1;
    }

    /** The length of the marker {@code out} ends with (it ends with {@code ~}), or 0. */
    private static int markerLengthAtEnd(StringBuilder out, char eightBitIntroducer) {
        int end = out.length();
        if (end < C1_MARKER_LENGTH) {
            return 0;
        }
        char kind = out.charAt(end - 2);
        if ((kind != '0' && kind != '1') || out.charAt(end - 3) != '0' || out.charAt(end - 4) != '2') {
            return 0;
        }
        char introducer = out.charAt(end - 5);
        if (introducer == CSI || introducer == eightBitIntroducer) {
            return C1_MARKER_LENGTH;
        }
        if (introducer == '[' && end >= ESC_MARKER_LENGTH && out.charAt(end - 6) == ESC) {
            return ESC_MARKER_LENGTH;
        }
        return 0;
    }
}
