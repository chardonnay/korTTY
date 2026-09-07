package de.kortty.core;

/**
 * The terminal's "Cursor blinks" preference.
 *
 * <p>The blink flag is encoded inside the {@code cursorStyle} string ({@code BLINK_BLOCK} vs
 * {@code STEADY_BLOCK}) because that is what the terminal widget consumes — but that same string is
 * also owned by color profiles and themes, every one of which ships a {@code BLINK_*} style. An
 * explicit "off" therefore used to be one profile copy away from being lost. {@link
 * de.kortty.model.GlobalSettings#getTerminalCursorBlink()} stores the user's choice in a field of its
 * own; this class is the shared vocabulary for reading it out of a style and writing it back into
 * one, so the settings dialog, the persistence layer and the terminal all agree.</p>
 */
public final class TerminalCursorBlink {

    private static final String DEFAULT_SHAPE = "BLOCK";
    private static final String BLINK_PREFIX = "BLINK_";
    private static final String STEADY_PREFIX = "STEADY_";

    private TerminalCursorBlink() {
    }

    /** Whether {@code cursorStyle} names a blinking cursor. */
    public static boolean isBlinking(String cursorStyle) {
        String normalized = normalize(cursorStyle);
        return normalized.startsWith(BLINK_PREFIX) || normalized.equals("BLINK");
    }

    /**
     * {@code cursorStyle} with the blink flag set to {@code blinking}, keeping its shape. A style
     * whose shape this build does not know is returned unchanged: the per-pane effect path must not
     * rewrite a shape a plugin supplied.
     */
    public static String withPreference(String cursorStyle, boolean blinking) {
        String suffix = shapeSuffix(cursorStyle);
        if (suffix == null) {
            return cursorStyle;
        }
        return (blinking ? BLINK_PREFIX : STEADY_PREFIX) + suffix;
    }

    /**
     * Like {@link #withPreference(String, boolean)} but for a value that gets SAVED: the result always
     * matches {@code blinking} and is always one of the six styles the terminal understands, so an
     * explicit choice is never dropped because the current style carries an unknown shape.
     */
    public static String withStoredPreference(String cursorStyle, boolean blinking) {
        String shape = shapeSuffix(cursorStyle);
        return (blinking ? BLINK_PREFIX : STEADY_PREFIX) + (shape != null ? shape : DEFAULT_SHAPE);
    }

    private static String shapeSuffix(String cursorStyle) {
        String normalized = normalize(cursorStyle);
        if (normalized.isEmpty()) {
            return DEFAULT_SHAPE;
        }
        int separator = normalized.indexOf('_');
        String suffix = separator >= 0 && separator < normalized.length() - 1
                ? normalized.substring(separator + 1)
                : normalized;
        return isSupportedShape(suffix) ? suffix : null;
    }

    private static boolean isSupportedShape(String suffix) {
        return DEFAULT_SHAPE.equals(suffix)
                || "UNDERLINE".equals(suffix)
                || "VERTICAL_BAR".equals(suffix);
    }

    private static String normalize(String cursorStyle) {
        return cursorStyle == null ? "" : cursorStyle.trim().toUpperCase();
    }
}
