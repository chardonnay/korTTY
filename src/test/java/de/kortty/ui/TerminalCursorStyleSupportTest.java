package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalCursorStyleSupportTest {

    @Test
    void caretBlinkingPeriodIsZeroForSteadyCursorStyle() {
        assertThat(TerminalCursorStyleSupport.caretBlinkingPeriodMs("STEADY_BLOCK", 505)).isEqualTo(0);
        assertThat(TerminalCursorStyleSupport.caretBlinkingPeriodMs("STEADY_VERTICAL_BAR", 505)).isEqualTo(0);
    }

    @Test
    void caretBlinkingPeriodKeepsConfiguredPeriodForBlinkingCursorStyle() {
        assertThat(TerminalCursorStyleSupport.caretBlinkingPeriodMs("BLINK_BLOCK", 505)).isEqualTo(505);
        assertThat(TerminalCursorStyleSupport.caretBlinkingPeriodMs("blink_underline", 505)).isEqualTo(505);
    }

    @Test
    void blinkingPreferenceKeepsRequestedShape() {
        assertThat(TerminalCursorStyleSupport.withBlinkingPreference("BLINK_BLOCK", false))
                .isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorStyleSupport.withBlinkingPreference("STEADY_VERTICAL_BAR", true))
                .isEqualTo("BLINK_VERTICAL_BAR");
    }

    @Test
    void blinkingPreferenceFallsBackToBlockWhenStyleIsBlank() {
        assertThat(TerminalCursorStyleSupport.withBlinkingPreference(null, false)).isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorStyleSupport.withBlinkingPreference(" ", true)).isEqualTo("BLINK_BLOCK");
    }

    @Test
    void unsupportedCursorStyleIsNotInvented() {
        assertThat(TerminalCursorStyleSupport.withBlinkingPreference("BLINK_DIAMOND", false))
                .isEqualTo("BLINK_DIAMOND");
    }

    @Test
    void storedBlinkingPreferenceKeepsKnownShape() {
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("BLINK_UNDERLINE", false))
                .isEqualTo("STEADY_UNDERLINE");
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("STEADY_BLOCK", true))
                .isEqualTo("BLINK_BLOCK");
    }

    @Test
    void storedBlinkingPreferenceIsNeverDroppedForUnknownShape() {
        // The saved setting must reflect the user's choice even when the stored style carries a shape
        // this build does not know — otherwise switching "Cursor blinks" off would not survive a restart.
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("BLINK_DIAMOND", false))
                .isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("BLINK", false))
                .isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("DIAMOND", true))
                .isEqualTo("BLINK_BLOCK");
    }

    @Test
    void themeCursorStyleAdoptsShapeButKeepsBlinkPreference() {
        // What the settings dialog does when a color profile is selected: every built-in profile ships
        // BLINK_*, and adopting that verbatim used to re-enable blinking behind the user's back.
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("BLINK_VERTICAL_BAR", false))
                .isEqualTo("STEADY_VERTICAL_BAR");
        assertThat(TerminalCursorStyleSupport.withStoredBlinkingPreference("BLINK_BLOCK", false))
                .isEqualTo("STEADY_BLOCK");
    }
}
