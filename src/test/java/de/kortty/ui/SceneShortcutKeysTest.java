package de.kortty.ui;

import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the platform rules of the main window's built-in scene chords (F12, terminal zoom and
 * Ctrl+Tab), which moved from two hand-written scene filters into the shortcut router unchanged.
 */
class SceneShortcutKeysTest {

    private static final boolean MAC = true;
    private static final boolean PC = false;

    @Test
    void zoomUsesCmdOnMacOsAndNeverOption() {
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.EQUALS, "=", "", false, false, false, true, MAC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomOut(key(KeyCode.MINUS, "-", "", false, false, false, true, MAC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomReset(key(KeyCode.DIGIT0, "0", "", false, false, false, true, MAC))).isTrue();
        // Option types characters such as | [ ] { } @ ~ \ on macOS, and Ctrl is not the zoom key there.
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.PLUS, "+", "", false, false, true, false, MAC))).isFalse();
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.PLUS, "+", "", false, true, false, false, MAC))).isFalse();
    }

    @Test
    void zoomUsesCtrlOrAltOnWindowsAndLinux() {
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.PLUS, "+", "", false, true, false, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.ADD, "+", "", false, false, true, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomOut(key(KeyCode.SUBTRACT, "-", "", false, true, false, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomReset(key(KeyCode.NUMPAD0, "0", "", false, false, true, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.PLUS, "+", "", false, false, false, true, PC))).isFalse();
        assertThat(SceneShortcutKeys.isZoomReset(key(KeyCode.DIGIT0, "0", "", false, false, false, false, PC))).isFalse();
    }

    @Test
    void zoomAlsoMatchesThePlusAndMinusCharactersOfOtherLayouts() {
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.UNDEFINED, "+", "", false, true, false, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomIn(key(KeyCode.UNDEFINED, "", "+", false, true, false, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomOut(key(KeyCode.UNDEFINED, "-", "", false, true, false, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isZoomOut(key(KeyCode.UNDEFINED, "", "-", false, true, false, false, PC))).isTrue();
    }

    @Test
    void theZoomChordsAreMutuallyExclusiveWithPlusFirst() {
        KeyPress plusOnMinusKey = key(KeyCode.MINUS, "+", "", false, true, false, false, PC);
        KeyPress minusOnDigitZero = key(KeyCode.DIGIT0, "-", "", false, true, false, false, PC);

        assertThat(SceneShortcutKeys.isZoomIn(plusOnMinusKey)).isTrue();
        assertThat(SceneShortcutKeys.isZoomOut(plusOnMinusKey)).isFalse();
        assertThat(SceneShortcutKeys.isZoomOut(minusOnDigitZero)).isTrue();
        assertThat(SceneShortcutKeys.isZoomReset(minusOnDigitZero)).isFalse();
    }

    @Test
    void zoomSwallowsWhateverItsKeyTypes() {
        assertThat(SceneShortcutKeys.ZOOM_RESIDUE.matches("à")).isTrue();
        assertThat(SceneShortcutKeys.ZOOM_RESIDUE.matches("~")).isTrue();
        assertThat(SceneShortcutKeys.ZOOM_RESIDUE.matches(KeyEvent.CHAR_UNDEFINED)).isTrue();
    }

    @Test
    void f12TogglesFullscreenWithAnyModifiers() {
        assertThat(SceneShortcutKeys.isFullscreenToggle(key(KeyCode.F12, "", "", false, false, false, false, MAC))).isTrue();
        assertThat(SceneShortcutKeys.isFullscreenToggle(key(KeyCode.F12, "", "", true, true, true, false, PC))).isTrue();
        assertThat(SceneShortcutKeys.isFullscreenToggle(key(KeyCode.F11, "", "", false, false, false, false, PC))).isFalse();
    }

    @Test
    void ctrlTabCyclesTabsWithCtrlOnEveryPlatform() {
        for (boolean macOs : new boolean[] {MAC, PC}) {
            KeyPress ctrlTab = key(KeyCode.TAB, "\t", "", false, true, false, false, macOs);
            KeyPress ctrlShiftTab = key(KeyCode.TAB, "\t", "", true, true, false, false, macOs);

            assertThat(SceneShortcutKeys.isNextTab(ctrlTab)).isTrue();
            assertThat(SceneShortcutKeys.isPreviousTab(ctrlTab)).isFalse();
            assertThat(SceneShortcutKeys.isPreviousTab(ctrlShiftTab)).isTrue();
            assertThat(SceneShortcutKeys.isNextTab(ctrlShiftTab)).isFalse();
            // Other modifiers were never checked, and still are not.
            assertThat(SceneShortcutKeys.isNextTab(key(KeyCode.TAB, "\t", "", false, true, true, true, macOs))).isTrue();
            // Cmd+Tab (the macOS app switcher) and a plain Tab stay with the system and the terminal.
            assertThat(SceneShortcutKeys.isNextTab(key(KeyCode.TAB, "\t", "", false, false, false, true, macOs))).isFalse();
            assertThat(SceneShortcutKeys.isNextTab(key(KeyCode.TAB, "\t", "", false, false, false, false, macOs))).isFalse();
        }
        assertThat(SceneShortcutKeys.TAB_RESIDUE.matches("\t")).isTrue();
        assertThat(SceneShortcutKeys.TAB_RESIDUE.matches("a")).isFalse();
    }

    private static KeyPress key(KeyCode code, String text, String character, boolean shift, boolean ctrl,
                                boolean alt, boolean meta, boolean macOs) {
        return new KeyPress(code, text, character, shift, ctrl, alt, meta, macOs);
    }
}
