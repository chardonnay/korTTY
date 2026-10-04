package de.kortty.model;

/**
 * Which text the XML files korTTY keeps its settings in can hold. XML 1.0 has no way to write most
 * control characters, U+FFFE, U+FFFF or half of a surrogate pair, not even as a character reference:
 * JAXB writes such a character anyway, and the next load of the whole file fails. For
 * {@code global-settings.xml} that means every setting falls back to its default, so free text the
 * user types into a setting is checked here before it is stored.
 *
 * <p>Pure, any thread.
 */
public final class XmlStorableText {

    private XmlStorableText() {
    }

    /**
     * The first code point of {@code text} an XML 1.0 file cannot hold, or {@code -1} when it can hold
     * all of them; {@code null} counts as storable. A lone surrogate is reported as its own value.
     */
    public static int firstUnstorableCodePoint(CharSequence text) {
        if (text == null) {
            return -1;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            if (!isStorableCodePoint(codePoint)) {
                return codePoint;
            }
            i += Character.charCount(codePoint);
        }
        return -1;
    }

    /** Whether an XML 1.0 file can hold all of {@code text}; {@code null} can be. */
    public static boolean isStorable(CharSequence text) {
        return firstUnstorableCodePoint(text) < 0;
    }

    /** XML 1.0's {@code Char} production; a lone surrogate is passed as its own value. */
    public static boolean isStorableCodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
