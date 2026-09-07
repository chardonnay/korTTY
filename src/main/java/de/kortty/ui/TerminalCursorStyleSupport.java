package de.kortty.ui;

import de.kortty.core.TerminalCursorBlink;

/**
 * UI-side view of the terminal cursor style. The blink rules themselves live in
 * {@link TerminalCursorBlink} because the model and the settings persistence need them too.
 */
final class TerminalCursorStyleSupport {

    private TerminalCursorStyleSupport() {
    }

    static boolean isBlinkingStyle(String cursorStyle) {
        return TerminalCursorBlink.isBlinking(cursorStyle);
    }

    static String withBlinkingPreference(String cursorStyle, boolean blinking) {
        return TerminalCursorBlink.withPreference(cursorStyle, blinking);
    }

    /** See {@link TerminalCursorBlink#withStoredPreference(String, boolean)}. */
    static String withStoredBlinkingPreference(String cursorStyle, boolean blinking) {
        return TerminalCursorBlink.withStoredPreference(cursorStyle, blinking);
    }

    static int caretBlinkingPeriodMs(String cursorStyle, int blinkingPeriodMs) {
        return isBlinkingStyle(cursorStyle) ? blinkingPeriodMs : 0;
    }
}
