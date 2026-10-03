package de.kortty.core;

import java.util.Locale;

/**
 * Makes text safe to show in a single line of the user interface: a tab title, a list row, a
 * notification. The text may come from the user, a project or teamwork file, or a remote program,
 * so it can carry characters that do not belong on screen:
 * <ul>
 *   <li>C0 controls (U+0000–U+001F), DEL (U+007F) and C1 controls (U+0080–U+009F), among them the
 *       escape character and line breaks that would split or restyle the line;</li>
 *   <li>the Unicode line and paragraph separators (U+2028, U+2029), which break a line like a line
 *       feed wherever the text ends up, for example in a desktop notification;</li>
 *   <li>the Unicode bidi controls (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069), which
 *       make a name read differently from the characters it is made of.</li>
 * </ul>
 * {@link #stripControlsAndBidi} removes them, {@link #truncate} caps the length without splitting a
 * character, {@link #sanitize} does both and trims, and {@link #toVisible} shows them as visible
 * placeholders instead, for a preview that must not hide what the text contains.
 */
public final class DisplayTextSanitizer {

    private DisplayTextSanitizer() {
    }

    /** True for a C0 control, DEL or a C1 control. */
    public static boolean isControl(int codePoint) {
        return Character.isISOControl(codePoint);
    }

    /** True for a Unicode bidi control (the {@code Bidi_Control} property). */
    public static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061C
            || codePoint == 0x200E || codePoint == 0x200F
            || (codePoint >= 0x202A && codePoint <= 0x202E)
            || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /** True for U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR, the Unicode line breaks that are no controls. */
    public static boolean isLineSeparator(int codePoint) {
        return codePoint == 0x2028 || codePoint == 0x2029;
    }

    /** True for a character {@link #stripControlsAndBidi} removes or turns into a space. */
    public static boolean isUnsafe(int codePoint) {
        return isControl(codePoint) || isBidiControl(codePoint) || isLineSeparator(codePoint);
    }

    /**
     * Removes every control and bidi control. A line break, tab, vertical tab, form feed, next-line,
     * line separator or paragraph separator character becomes a space, so the words on either side of it stay apart; a CR LF pair, or a
     * break right after a space, adds no second space.
     *
     * @return the cleaned text; {@code ""} for {@code null}
     */
    public static String stripControlsAndBidi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder clean = null;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (isUnsafe(codePoint)) {
                if (clean == null) {
                    clean = new StringBuilder(text.length()).append(text, 0, i);
                }
                if (isLineOrTabBreak(codePoint) && (clean.isEmpty() || clean.charAt(clean.length() - 1) != ' ')) {
                    clean.append(' ');
                }
            } else if (clean != null) {
                clean.appendCodePoint(codePoint);
            }
            i += width;
        }
        return clean != null ? clean.toString() : text;
    }

    /**
     * Cuts {@code text} to at most {@code maxCodePoints} characters. A character outside the Basic
     * Multilingual Plane counts once and is never split into half a surrogate pair.
     *
     * @return the text, shortened if needed; {@code ""} for {@code null} or a limit below one
     */
    public static String truncate(String text, int maxCodePoints) {
        if (text == null || maxCodePoints <= 0) {
            return "";
        }
        if (text.length() <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, Math.min(maxCodePoints, text.codePointCount(0, text.length())));
        return text.substring(0, end);
    }

    /**
     * {@link #stripControlsAndBidi}, then trims, caps the result at {@code maxCodePoints} characters
     * and trims again, so a cut never leaves a trailing space.
     *
     * @return the cleaned text; {@code ""} for {@code null} or text that held nothing visible
     */
    public static String sanitize(String text, int maxCodePoints) {
        return truncate(stripControlsAndBidi(text).strip(), maxCodePoints).strip();
    }

    /**
     * Shows every control and bidi control instead of removing it: a C0 control becomes its Unicode
     * control picture (U+2400–U+241F, for example {@code ␛} for escape and {@code ␊} for a line
     * feed), DEL becomes {@code ␡}, and a C1 control, a line or paragraph separator or a bidi control
     * becomes a {@code <U+XXXX>} tag. All
     * other characters are kept.
     *
     * @return the visible form; {@code ""} for {@code null}
     */
    public static String toVisible(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder visible = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint < 0x20) {
                visible.appendCodePoint(0x2400 + codePoint);
            } else if (codePoint == 0x7F) {
                visible.append('␡');
            } else if (isUnsafe(codePoint)) {
                visible.append(String.format(Locale.ROOT, "<U+%04X>", codePoint));
            } else {
                visible.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return visible.toString();
    }

    private static boolean isLineOrTabBreak(int codePoint) {
        return codePoint == '\t' || codePoint == '\n' || codePoint == 0x0B || codePoint == '\f'
            || codePoint == '\r' || codePoint == 0x85 || isLineSeparator(codePoint);
    }
}
