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
