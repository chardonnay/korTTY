package de.kortty.ui.actions;

import de.kortty.core.DisplayTextSanitizer;

/**
 * Makes a text fit for one row of the command palette. Row texts can come from shared teamwork
 * files or from a program on a server, so control characters and Unicode bidi controls are removed
 * (they could split the row or make a name read like another one), and the text is cut to
 * {@link #MAX_LENGTH} characters. Every {@link PaletteEntry} applies it to all its texts.
 */
public final class PaletteText {

    /** The longest text a palette row shows, in characters. */
    public static final int MAX_LENGTH = 120;

    private PaletteText() {
    }

    /** The text without control and bidi characters, trimmed and at most {@link #MAX_LENGTH} long; never null. */
    public static String clean(String text) {
        return DisplayTextSanitizer.sanitize(text, MAX_LENGTH);
    }
}
