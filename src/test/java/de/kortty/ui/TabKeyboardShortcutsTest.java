package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The platform matrix of the Cmd/Ctrl+1..9 tab jump: Cmd with Shift tolerated on macOS, exactly
 * Ctrl on Windows and Linux (so AltGr, Ctrl+Shift+6 and plain digits reach the terminal), 1 to 8 by
 * position and 9 for the last tab, top row and numpad. Toolkit-free: key presses are built directly.
 */
class TabKeyboardShortcutsTest {

    private static final boolean MAC = true;
    private static final boolean PC = false;

    @Test
    void cmdDigitJumpsOnMacOs() {
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT1), 5)).isEqualTo(0);
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT8), 8)).isEqualTo(7);
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.NUMPAD3), 5)).isEqualTo(2);
        // 9 is always the last tab, however many there are.
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT9), 3)).isEqualTo(2);
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT9), 12)).isEqualTo(11);
        // Shift is tolerated: an AZERTY Mac reaches its digits with Shift.
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", true, false, false, true, MAC), 5))
            .isEqualTo(0);
    }

    @Test
    void ctrlOptionOrNoCmdIsNotAJumpOnMacOs() {
        // Ctrl+1 is the Ctrl key on macOS, not Cmd.
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, true, false, false, MAC), 5))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, true, false, true, MAC), 5))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        // Option types characters on macOS.
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, false, true, true, MAC), 5))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, false, false, false, MAC), 5))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
    }

    @Test
    void exactlyCtrlDigitJumpsOnWindowsAndLinux() {
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.DIGIT1), 4)).isEqualTo(0);
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.NUMPAD2), 4)).isEqualTo(1);
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.DIGIT9), 4)).isEqualTo(3);
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.NUMPAD9), 1)).isEqualTo(0);
    }

    @Test
    void altGrCtrlShiftAndMetaDigitsStayWithTheTerminalOnWindowsAndLinux() {
        // AltGr arrives as Ctrl+Alt (AltGr+2 types @ on a German layout).
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT2, "2", false, true, true, false, PC), 4))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        // Ctrl+Shift+6 is the Cisco break sequence.
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT6, "6", true, true, false, false, PC), 9))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, false, false, false, PC), 4))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, false, false, true, PC), 4))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, true, false, true, PC), 4))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.jumpIndex(key(KeyCode.DIGIT1, "1", false, false, true, false, PC), 4))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
    }

    @Test
    void aJumpBeyondTheLastTabIsAConsumedNoOp() {
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT5), 3)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.DIGIT4), 3)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        assertThat(TabKeyboardShortcuts.jumpIndex(ctrl(KeyCode.DIGIT9), 0)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        assertThat(TabKeyboardShortcuts.jumpIndex(cmd(KeyCode.DIGIT1), 0)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        // Still a jump chord, so the router consumes it.
        assertThat(TabKeyboardShortcuts.slotOf(cmd(KeyCode.DIGIT5))).isEqualTo(5);
    }

    @Test
    void slotsMapToPositionsAndNineToTheLastTab() {
        for (int slot = 1; slot <= 8; slot++) {
            assertThat(TabKeyboardShortcuts.indexForSlot(slot, 8)).isEqualTo(slot - 1);
            assertThat(TabKeyboardShortcuts.indexForSlot(slot, slot - 1)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        }
        assertThat(TabKeyboardShortcuts.indexForSlot(9, 9)).isEqualTo(8);
        assertThat(TabKeyboardShortcuts.indexForSlot(9, 1)).isEqualTo(0);
        assertThat(TabKeyboardShortcuts.indexForSlot(0, 5)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
        assertThat(TabKeyboardShortcuts.indexForSlot(10, 5)).isEqualTo(TabKeyboardShortcuts.NO_TAB);
    }

    @Test
    void zeroAndOtherKeysAreNotJumps() {
        // Cmd/Ctrl+0 resets the zoom.
        assertThat(TabKeyboardShortcuts.slotOf(cmd(KeyCode.DIGIT0))).isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.slotOf(ctrl(KeyCode.NUMPAD0))).isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.slotOf(ctrl(KeyCode.T))).isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(TabKeyboardShortcuts.slotOf(ctrl(KeyCode.F1))).isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
    }

    @Test
    void aDigitKeyThatTypesPlusOrMinusStaysTheZoomKey() {
        // The AZERTY 6 key types '-', so Ctrl+that key zooms out rather than jumping to tab 6.
        KeyPress azertySix = key(KeyCode.DIGIT6, "-", false, true, false, false, PC);
        assertThat(TabKeyboardShortcuts.slotOf(azertySix)).isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        assertThat(SceneShortcutKeys.isZoomOut(azertySix)).isTrue();
        assertThat(TabKeyboardShortcuts.slotOf(new KeyPress(KeyCode.DIGIT1, "", "+", false, false, false, true, MAC)))
            .isEqualTo(TabKeyboardShortcuts.NOT_A_JUMP);
        // The AZERTY 1 key types '&' and still jumps.
        assertThat(TabKeyboardShortcuts.slotOf(key(KeyCode.DIGIT1, "&", false, true, false, false, PC))).isEqualTo(1);
    }

    @Test
    void theJumpResidueIsSwallowedOnceWhateverTheLayoutTypes() {
        assertThat(TabKeyboardShortcuts.JUMP_RESIDUE.matches("1")).isTrue();
        assertThat(TabKeyboardShortcuts.JUMP_RESIDUE.matches("&")).isTrue();
        assertThat(TabKeyboardShortcuts.JUMP_RESIDUE.matches(KeyEvent.CHAR_UNDEFINED)).isTrue();

        List<Integer> selected = new ArrayList<>();
        SceneShortcutRouter router = jumpRouter(PC, selected, 4);

        KeyEvent jump = keyPressed(KeyCode.DIGIT2, "2", false, true, false, false);
        router.onKeyPressed(jump);
        KeyEvent residue = keyTyped("2");
        router.onKeyTyped(residue);
        KeyEvent next = keyTyped("2");
        router.onKeyTyped(next);

        assertThat(selected).containsExactly(1);
        assertThat(jump.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
        // Broadcast mode mirrors KEY_TYPED to the other panes, so only the chord's own residue may go.
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void aJumpWithoutKeyTypedDoesNotEatTheNextCharacter() {
        List<Integer> selected = new ArrayList<>();
        SceneShortcutRouter router = jumpRouter(MAC, selected, 2);

        // Cmd+5 with two tabs: consumed, nothing selected, and macOS delivers no KEY_TYPED.
        KeyEvent outOfRange = keyPressed(KeyCode.DIGIT5, "5", false, false, false, true);
        router.onKeyPressed(outOfRange);
        router.onKeyPressed(keyPressed(KeyCode.A, "a", false, false, false, false));
        KeyEvent typed = keyTyped("a");
        router.onKeyTyped(typed);

        assertThat(outOfRange.isConsumed()).isTrue();
        assertThat(selected).isEmpty();
        assertThat(typed.isConsumed()).isFalse();
    }

    @Test
    void ctrlShiftSixPassesThroughTheRouter() {
        List<Integer> selected = new ArrayList<>();
        SceneShortcutRouter router = jumpRouter(PC, selected, 9);

        KeyEvent ciscoBreak = keyPressed(KeyCode.DIGIT6, "6", true, true, false, false);
        router.onKeyPressed(ciscoBreak);
        KeyEvent typed = keyTyped("\u001e");
        router.onKeyTyped(typed);

        assertThat(selected).isEmpty();
        assertThat(ciscoBreak.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    /** The router entries MainWindow registers, with tab selection recorded instead of performed. */
    private static SceneShortcutRouter jumpRouter(boolean macOs, List<Integer> selected, int tabCount) {
        SceneShortcutRouter router = new SceneShortcutRouter(macOs)
            .consume(SceneShortcutKeys::isZoomOut, SceneShortcutRouter.ALWAYS, () -> { }, Residue.anyCharacter());
        for (int slot = 1; slot <= TabKeyboardShortcuts.SLOT_COUNT; slot++) {
            int jumpSlot = slot;
            router.consume(press -> TabKeyboardShortcuts.slotOf(press) == jumpSlot, SceneShortcutRouter.ALWAYS,
                () -> {
                    int index = TabKeyboardShortcuts.indexForSlot(jumpSlot, tabCount);
                    if (index != TabKeyboardShortcuts.NO_TAB) {
                        selected.add(index);
                    }
                }, TabKeyboardShortcuts.JUMP_RESIDUE);
        }
        return router;
    }

    private static KeyPress cmd(KeyCode code) {
        return key(code, "", false, false, false, true, MAC);
    }

    private static KeyPress ctrl(KeyCode code) {
        return key(code, "", false, true, false, false, PC);
    }

    private static KeyPress key(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt, boolean meta,
                                boolean macOs) {
        return new KeyPress(code, text, KeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta, macOs);
    }

    private static KeyEvent keyPressed(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt,
                                       boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }
}
