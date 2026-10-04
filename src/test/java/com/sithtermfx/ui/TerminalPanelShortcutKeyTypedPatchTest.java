package com.sithtermfx.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression coverage for the shortcut KEY_TYPED fix (a korTTY patch until it shipped upstream in
 * SithTermFX 1.2.3; this pins the released behaviour against a regressing upgrade): the character
 * half of a Meta/Cmd chord (e.g. the "d" of the Cmd+Shift+D dashboard accelerator) must never be
 * written to the pty, while plain typing and Windows AltGr input (reported as Ctrl+Alt) must.
 */
class TerminalPanelShortcutKeyTypedPatchTest {

    @Test
    void suppressesCharacterOfMetaShortcutChord() {
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("d", false, false, false, true))).isTrue();
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("d", true, false, false, true))).isTrue();
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("`", false, false, false, true))).isTrue();
    }

    @Test
    void keepsPlainTypedCharacters() {
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("d", false, false, false, false))).isFalse();
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("D", true, false, false, false))).isFalse();
    }

    @Test
    void keepsAltGrComposedCharacters() {
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("@", false, true, true, false))).isFalse();
        assertThat(TerminalPanel.isShortcutChordCharacter(keyTyped("€", false, true, true, false))).isFalse();
    }

    private static KeyEvent keyTyped(
            String character, boolean shiftDown, boolean controlDown, boolean altDown, boolean metaDown) {
        return new KeyEvent(
                KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED,
                shiftDown, controlDown, altDown, metaDown);
    }
}
