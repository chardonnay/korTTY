package de.kortty.model;

/**
 * A named colour palette for the generated journal page. Mirrors {@link ChatColorProfile}: a plain
 * record of colour slots plus an id, resolved into CSS custom properties by the renderer.
 *
 * <p>Marker colours are deliberately absent — those come from the marker definitions now, so a
 * scheme only styles the page chrome around them.</p>
 */
public record SessionJournalPageScheme(
        String id,
        String name,
        /** True for the entries that derive their colours instead of carrying fixed ones. */
        boolean derived,
        String bg,
        String surface,
        String surface2,
        String border,
        String text,
        String muted,
        String accent,
        String input,
        String output,
        String mark,
        String markCurrent) {

    /** The id of the default scheme: the page's own dark/light pair driven by the OS setting. */
    public static final String ID_AUTO = "auto";

    /** The id of the scheme that follows the terminal theme's background and foreground. */
    public static final String ID_THEME = "theme";

    public boolean isAuto() {
        return ID_AUTO.equals(id);
    }
}
