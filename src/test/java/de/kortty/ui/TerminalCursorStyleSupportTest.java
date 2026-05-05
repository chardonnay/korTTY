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
}
