package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalCursorBlinkTest {

    @Test
    void readsTheBlinkFlagOutOfAStyle() {
        assertThat(TerminalCursorBlink.isBlinking("BLINK_BLOCK")).isTrue();
        assertThat(TerminalCursorBlink.isBlinking("blink_vertical_bar")).isTrue();
        assertThat(TerminalCursorBlink.isBlinking("BLINK")).isTrue();
        assertThat(TerminalCursorBlink.isBlinking("STEADY_BLOCK")).isFalse();
        assertThat(TerminalCursorBlink.isBlinking("BLOCK")).isFalse();
        assertThat(TerminalCursorBlink.isBlinking(null)).isFalse();
    }

    @Test
    void writesThePreferenceBackWithoutChangingTheShape() {
        assertThat(TerminalCursorBlink.withPreference("BLINK_UNDERLINE", false)).isEqualTo("STEADY_UNDERLINE");
        assertThat(TerminalCursorBlink.withPreference("STEADY_VERTICAL_BAR", true)).isEqualTo("BLINK_VERTICAL_BAR");
        assertThat(TerminalCursorBlink.withPreference("BLINK_BLOCK", true)).isEqualTo("BLINK_BLOCK");
    }

    @Test
    void keepsAnUnknownShapeForTheLiveStyleButNotForTheStoredOne() {
        // A plugin-supplied shape stays as it is where the style only decorates one pane...
        assertThat(TerminalCursorBlink.withPreference("BLINK_HOLLOW", false)).isEqualTo("BLINK_HOLLOW");
        // ...but a saved preference must always match the checkbox and name a style the terminal knows.
        assertThat(TerminalCursorBlink.withStoredPreference("BLINK_HOLLOW", false)).isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorBlink.withStoredPreference(null, false)).isEqualTo("STEADY_BLOCK");
        assertThat(TerminalCursorBlink.withStoredPreference("STEADY_UNDERLINE", true)).isEqualTo("BLINK_UNDERLINE");
    }
}
