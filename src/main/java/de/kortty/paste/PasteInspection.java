package de.kortty.paste;

import java.util.Locale;

/**
 * What a paste holds, measured in one pass over the text as it came from the clipboard: line breaks,
 * lines, its UTF-8 size, control characters, invisible direction-changing (bidi) characters and
 * bracketed-paste markers. {@link PasteDecision} turns it into {@link PasteReason}s, and the
 * confirmation shows it together with a {@link #preview(int, int) preview} in which nothing hidden
 * stays hidden.
 *
 * <p>The character classes:
 * <ul>
 *   <li>{@link #isControlCharacter(int) Control characters}: C0 except tab, line feed and carriage
 *       return, DEL and C1 ({@code U+0080..U+009F}). Escape counts, so a pasted escape sequence or
 *       an embedded bracketed-paste marker counts as well. A tty acts on some of them (Ctrl+C,
 *       Ctrl+Z, Ctrl+S) before the program sees the paste.</li>
 *   <li>{@link #isBidiControl(int) Bidi controls}: the marks and embeddings, overrides and isolates
 *       that reorder how text is displayed, so the preview would show something other than what the
 *       program receives.</li>
 *   <li>{@link #isInvisible(int) Invisible characters}: zero-width spaces and joiners, the soft
 *       hyphen, line and paragraph separators and tag characters. They are shown as {@code <U+XXXX>}
 *       in the preview but are not counted, since emoji and some scripts use them.</li>
 * </ul>
 *
 * <p>Pure, any thread.
 */
public final class PasteInspection {

    /** The lines a confirmation previews by default. */
    public static final int DEFAULT_PREVIEW_LINES = 15;

    /** The characters a confirmation previews by default. */
    public static final int DEFAULT_PREVIEW_CHARS = 4000;

    private static final char ESC = '\u001b';

    private static final char CSI = '\u009b';

    private static final char DEL = '\u007f';

    /** The Unicode "symbol for null"; the control picture of a C0 character {@code c} is this plus c. */
    private static final char CONTROL_PICTURES = '\u2400';

    /** The Unicode "symbol for delete". */
    private static final char DELETE_PICTURE = '\u2421';

    private static final PasteInspection EMPTY = new PasteInspection("", false, false, 0, 0, 0, 0, false);

    private final String text;

    private final boolean containsLineBreak;

    private final boolean endsWithLineBreak;

    private final int lineCount;

    private final long utf8Bytes;

    private final int controlCharCount;

    private final int bidiControlCount;

    private final boolean containsBracketMarker;

    private PasteInspection(String text, boolean containsLineBreak, boolean endsWithLineBreak, int lineCount,
            long utf8Bytes, int controlCharCount, int bidiControlCount, boolean containsBracketMarker) {
        this.text = text;
        this.containsLineBreak = containsLineBreak;
        this.endsWithLineBreak = endsWithLineBreak;
        this.lineCount = lineCount;
        this.utf8Bytes = utf8Bytes;
        this.controlCharCount = controlCharCount;
        this.bidiControlCount = bidiControlCount;
        this.containsBracketMarker = containsBracketMarker;
    }

    /**
     * Measures {@code text}.
     *
     * @param text the text as it came from the clipboard, before any sanitizing; null counts as empty
     */
    public static PasteInspection of(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        int length = text.length();
        int lineBreaks = 0;
        long bytes = 0;
        int controls = 0;
        int bidi = 0;
        boolean marker = false;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
                if (c == '\r') {
                    lineBreaks++;
                } else if (c == '\n') {
                    // The LF of a CR LF pair ends the line its CR already ended.
                    if (i == 0 || text.charAt(i - 1) != '\r') {
                        lineBreaks++;
                    }
                } else if (c == '~') {
                    marker |= endsBracketMarker(text, i);
                } else if (isControlCharacter(c)) {
                    controls++;
                }
            } else if (c < 0x800) {
                bytes += 2;
                if (isControlCharacter(c)) {
                    controls++;
                } else if (isBidiControl(c)) {
                    bidi++;
                }
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                // No control or bidi character lies outside the Basic Multilingual Plane.
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                // UTF-8 cannot encode a lone surrogate; Java writes '?' for it.
                bytes++;
            } else {
                bytes += 3;
                if (isBidiControl(c)) {
                    bidi++;
                }
            }
        }
        char last = text.charAt(length - 1);
        boolean endsWithBreak = last == '\r' || last == '\n';
        int lines = lineBreaks + (endsWithBreak ? 0 : 1);
        return new PasteInspection(text, lineBreaks > 0, endsWithBreak, lines, bytes, controls, bidi, marker);
    }

    /**
     * Whether the text holds a line break ({@code CR}, {@code LF} or {@code CR LF}), a single
     * trailing one included: unbracketed, each one acts as a pressed Enter.
     */
    public boolean containsLineBreak() {
        return containsLineBreak;
    }

    /** Whether the text ends with a line break, so its last line would run at once. */
    public boolean endsWithLineBreak() {
        return endsWithLineBreak;
    }

    /**
     * The number of lines: one per line break, plus a last line that does not end with one. A text
     * with a single trailing line break has one line; the empty text has none.
     */
    public int lineCount() {
        return lineCount;
    }

    /** The size of the text in UTF-8, counted without encoding it. */
    public long utf8Bytes() {
        return utf8Bytes;
    }

    /** How many {@link #isControlCharacter(int) control characters} the text holds. */
    public int controlCharCount() {
        return controlCharCount;
    }

    /** How many {@link #isBidiControl(int) bidi controls} the text holds. */
    public int bidiControlCount() {
        return bidiControlCount;
    }

    /** Whether the text has control or bidi characters at all. */
    public boolean containsControlCharacters() {
        return controlCharCount + bidiControlCount > 0;
    }

    /**
     * Whether the text holds a bracketed-paste start or end marker in its 7-bit ({@code ESC [ 2 0 x ~})
     * or 8-bit ({@code U+009B 2 0 x ~}) form. korTTY removes them before the paste is sent
     * ({@link PasteSanitizer}).
     */
    public boolean containsBracketMarker() {
        return containsBracketMarker;
    }

    /**
     * {@link #preview(int, int)} with {@link #DEFAULT_PREVIEW_LINES} and {@link #DEFAULT_PREVIEW_CHARS}.
     */
    public Preview preview() {
        return preview(DEFAULT_PREVIEW_LINES, DEFAULT_PREVIEW_CHARS);
    }

    /**
     * The start of the text for display. Every line break ({@code CR}, {@code LF}, {@code CR LF})
     * becomes one {@code LF}, and a trailing one is left out. A C0 control character is shown as its
     * Unicode control picture (Escape as {@code ␛}, DEL as {@code ␡}); C1, bidi and
     * {@link #isInvisible(int) invisible} characters as a {@code <U+XXXX>} tag. Tabs stay.
     *
     * @param maxLines at most this many lines are shown (at least one)
     * @param maxChars the preview text is at most this long (at least one character); a tag or a
     *     surrogate pair is never cut in half
     */
    public Preview preview(int maxLines, int maxChars) {
        int lineLimit = Math.max(1, maxLines);
        int charLimit = Math.max(1, maxChars);
        StringBuilder out = new StringBuilder(Math.min(text.length(), charLimit) + 16);
        int lines = 1;
        boolean truncated = false;
        int length = text.length();
        int i = 0;
        while (i < length) {
            int codePoint = text.codePointAt(i);
            if (codePoint == '\r' || codePoint == '\n') {
                int next = i + 1;
                if (codePoint == '\r' && next < length && text.charAt(next) == '\n') {
                    next++;
                }
                if (next >= length) {
                    break;
                }
                if (lines >= lineLimit || out.length() + 1 > charLimit) {
                    truncated = true;
                    break;
                }
                out.append('\n');
                lines++;
                i = next;
                continue;
            }
            String visible = visibleForm(codePoint);
            int width = Character.charCount(codePoint);
            if (out.length() + (visible != null ? visible.length() : width) > charLimit) {
                truncated = true;
                break;
            }
            if (visible != null) {
                out.append(visible);
            } else {
                out.appendCodePoint(codePoint);
            }
            i += width;
        }
        return new Preview(out.toString(), truncated);
    }

    /**
     * A control character a paste should not carry unnoticed: C0 except tab, line feed and carriage
     * return; DEL; and C1 ({@code U+0080..U+009F}).
     */
    public static boolean isControlCharacter(int codePoint) {
        if (codePoint < 0x20) {
            return codePoint != '\t' && codePoint != '\n' && codePoint != '\r' && codePoint >= 0;
        }
        return codePoint == DEL || (codePoint >= 0x80 && codePoint <= 0x9f);
    }

    /**
     * A character that changes the direction text is displayed in: the Arabic letter mark
     * ({@code U+061C}), the left-to-right and right-to-left marks ({@code U+200E}, {@code U+200F}),
     * the embeddings and overrides ({@code U+202A..U+202E}) and the isolates
     * ({@code U+2066..U+2069}).
     */
    public static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061c
            || codePoint == 0x200e
            || codePoint == 0x200f
            || (codePoint >= 0x202a && codePoint <= 0x202e)
            || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /**
     * A character with no visible glyph that the preview shows as a tag but that does not make a paste
     * ask: the soft hyphen, the Mongolian vowel separator, the zero-width space, non-joiner and joiner,
     * the line and paragraph separators, the word joiner and invisible operators
     * ({@code U+2060..U+2064}), the byte order mark and the tag characters
     * ({@code U+E0000..U+E007F}).
     */
    public static boolean isInvisible(int codePoint) {
        return codePoint == 0x00ad
            || codePoint == 0x180e
            || (codePoint >= 0x200b && codePoint <= 0x200d)
            || codePoint == 0x2028
            || codePoint == 0x2029
            || (codePoint >= 0x2060 && codePoint <= 0x2064)
            || codePoint == 0xfeff
            || (codePoint >= 0xe0000 && codePoint <= 0xe007f);
    }

    /**
     * How the preview shows {@code codePoint}, or null when it is shown as itself: a C0 control
     * character (not tab, line feed or carriage return) as its control picture, DEL as {@code ␡}, and
     * C1, bidi, {@link #isInvisible(int) invisible} and lone surrogate characters as {@code <U+XXXX>}.
     */
    public static String visibleForm(int codePoint) {
        if (codePoint >= 0 && codePoint < 0x20) {
            if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
                return null;
            }
            return String.valueOf((char) (CONTROL_PICTURES + codePoint));
        }
        if (codePoint == DEL) {
            return String.valueOf(DELETE_PICTURE);
        }
        if (isControlCharacter(codePoint) || isBidiControl(codePoint) || isInvisible(codePoint)
                || (codePoint <= Character.MAX_VALUE && Character.isSurrogate((char) codePoint))) {
            return String.format(Locale.ROOT, "<U+%04X>", codePoint);
        }
        return null;
    }

    /** Whether the {@code ~} at {@code tilde} ends {@code ESC [ 2 0 0 ~}, {@code ESC [ 2 0 1 ~} or their C1 form. */
    private static boolean endsBracketMarker(String text, int tilde) {
        if (tilde < 4) {
            return false;
        }
        char kind = text.charAt(tilde - 1);
        if ((kind != '0' && kind != '1') || text.charAt(tilde - 2) != '0' || text.charAt(tilde - 3) != '2') {
            return false;
        }
        char introducer = text.charAt(tilde - 4);
        return introducer == CSI || (introducer == '[' && tilde >= 5 && text.charAt(tilde - 5) == ESC);
    }

    /**
     * The start of a paste for display.
     *
     * @param text what to show; line breaks are {@code LF}, hidden characters are visible
     * @param truncated whether the paste goes on after {@code text}
     */
    public record Preview(String text, boolean truncated) {
    }
}
