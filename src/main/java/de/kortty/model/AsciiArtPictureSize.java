package de.kortty.model;

/**
 * The character grid an AI-drawn ASCII picture is fitted into. Every preset is twice as wide as it
 * is tall in characters, which at the converter's 1:2 cell aspect makes the visible picture square —
 * the model therefore always draws on the same square canvas and only the converter knows the size.
 */
public enum AsciiArtPictureSize {
    SMALL(40, 20),
    MEDIUM(60, 30),
    LARGE(80, 40),
    XL(100, 50);

    /** Default grid; equals the limits the AI Picture tab had before the size selector existed. */
    public static final AsciiArtPictureSize DEFAULT = MEDIUM;

    private final int columns;
    private final int rows;

    AsciiArtPictureSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

}
