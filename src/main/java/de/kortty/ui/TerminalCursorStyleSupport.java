package de.kortty.ui;

final class TerminalCursorStyleSupport {

    private static final String DEFAULT_SHAPE = "BLOCK";
    private static final String BLINK_PREFIX = "BLINK_";
    private static final String STEADY_PREFIX = "STEADY_";

    private TerminalCursorStyleSupport() {
    }

    static boolean isBlinkingStyle(String cursorStyle) {
        String normalized = normalize(cursorStyle);
        return normalized.startsWith(BLINK_PREFIX) || normalized.equals("BLINK");
    }

    static String withBlinkingPreference(String cursorStyle, boolean blinking) {
        String suffix = shapeSuffix(cursorStyle);
        if (suffix == null) {
            return cursorStyle;
        }
        return (blinking ? BLINK_PREFIX : STEADY_PREFIX) + suffix;
    }

    /**
     * Same as {@link #withBlinkingPreference(String, boolean)} but for the preference that gets SAVED:
     * the result always matches {@code blinking} and is always one of the six styles the terminal
     * understands. An explicit "cursor blinks" choice must never be silently dropped (nor persisted as
     * a style the terminal will refuse) just because the current style carries an unknown shape —
     * hand-edited XML, a style written by another version, a plugin-supplied style. Unknown shapes keep
     * their spelling in the per-pane effect path above; here they fall back to the default shape.
     */
    static String withStoredBlinkingPreference(String cursorStyle, boolean blinking) {
        String shape = shapeSuffix(cursorStyle);
        return (blinking ? BLINK_PREFIX : STEADY_PREFIX) + (shape != null ? shape : DEFAULT_SHAPE);
    }

    static int caretBlinkingPeriodMs(String cursorStyle, int blinkingPeriodMs) {
        return isBlinkingStyle(cursorStyle) ? blinkingPeriodMs : 0;
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
