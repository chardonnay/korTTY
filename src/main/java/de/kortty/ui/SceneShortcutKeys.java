package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;

/**
 * The main window's built-in scene chords that are matched by key facts rather than by an
 * accelerator constant: F12 fullscreen, terminal zoom and Ctrl+Tab tab cycling. Pure functions of a
 * {@link KeyPress}, so the platform rules are unit-tested without the JavaFX toolkit.
 */
final class SceneShortcutKeys {

    /**
     * The zoom keys sit on layout-dependent keys ({@code à} on the AZERTY 0 key, AltGr's {@code ~} on
     * the German plus key), so a zoom chord swallows whatever its KEY_TYPED carries, as it always did.
     */
    static final Residue ZOOM_RESIDUE = Residue.anyCharacter();

    /** Ctrl+Tab's KEY_TYPED, where the platform delivers one. */
    static final Residue TAB_RESIDUE = Residue.of("\t");

    private SceneShortcutKeys() {
    }

    /** F12 toggles fullscreen, with any modifiers (F11 is reserved by macOS for "Show Desktop"). */
    static boolean isFullscreenToggle(KeyPress press) {
        return press.code() == KeyCode.F12;
    }

    /**
     * Cmd on macOS, Ctrl or Alt elsewhere. Option (Alt) on macOS must not zoom: it types characters
     * such as |, [, ], {, }, @, ~ and \.
     */
    static boolean hasZoomModifier(KeyPress press) {
        return press.macOs() ? press.meta() : (press.ctrl() || press.alt());
    }

    /** Zoom modifier + Plus, through the key codes and characters of the various keyboards. */
    static boolean isZoomIn(KeyPress press) {
        if (!hasZoomModifier(press)) {
            return false;
        }
        KeyCode code = press.code();
        return code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS
            || "+".equals(press.text()) || "+".equals(press.character());
    }

    /** Zoom modifier + Minus; a key that also reads as Plus zooms in instead. */
    static boolean isZoomOut(KeyPress press) {
        if (!hasZoomModifier(press) || isZoomIn(press)) {
            return false;
        }
        KeyCode code = press.code();
        return code == KeyCode.MINUS || code == KeyCode.SUBTRACT
            || "-".equals(press.text()) || "-".equals(press.character());
    }

    /** Zoom modifier + 0 resets the zoom; a key that also reads as Plus or Minus zooms instead. */
    static boolean isZoomReset(KeyPress press) {
        if (!hasZoomModifier(press) || isZoomIn(press) || isZoomOut(press)) {
            return false;
        }
        return press.code() == KeyCode.DIGIT0 || press.code() == KeyCode.NUMPAD0;
    }

    /** Ctrl+Tab, with Ctrl on macOS too; other modifiers apart from Shift are ignored. */
    static boolean isNextTab(KeyPress press) {
        return press.ctrl() && press.code() == KeyCode.TAB && !press.shift();
    }

    /** Ctrl+Shift+Tab, with Ctrl on macOS too. */
    static boolean isPreviousTab(KeyPress press) {
        return press.ctrl() && press.code() == KeyCode.TAB && press.shift();
    }
}
