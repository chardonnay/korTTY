package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import org.jetbrains.annotations.NotNull;

/**
 * Cmd+1..9 on macOS and Ctrl+1..9 on Windows and Linux jump to a tab of the main window: 1 to 8
 * select the tab at that position, 9 the last tab, with the digits of the top row or the numpad.
 * Pure functions of a {@link KeyPress}, so the platform rules are unit-tested without the JavaFX
 * toolkit; MainWindow registers one router entry per slot.
 *
 * <p>On macOS the chord is Cmd without Ctrl or Option. Shift is tolerated: no Cmd+Shift+digit
 * command exists, macOS takes Cmd+Shift+3/4/5 for screenshots before the app sees them, and a
 * French (AZERTY) Mac reaches its digits with Shift. On Windows and Linux the chord is exactly
 * Ctrl: AltGr arrives as Ctrl+Alt and Ctrl+Shift+6 is the Cisco break sequence, so both have to
 * reach the terminal. SithTermFX never encoded Ctrl+digit, so the shell loses nothing it received.
 */
final class TabKeyboardShortcuts {

    /** The number of jump slots: 1 to 8 pick a position, 9 the last tab. */
    static final int SLOT_COUNT = 9;

    /** {@link #slotOf} for a key that is not a jump chord. */
    static final int NOT_A_JUMP = 0;

    /** {@link #indexForSlot} for a jump chord with no tab at its position: consumed, nothing selected. */
    static final int NO_TAB = -1;

    /**
     * Whatever the chord's KEY_TYPED carries: the digit, the character the layout puts on that key
     * ({@code &} on the AZERTY 1 key) or a control character. Only the KEY_TYPED right after the
     * consumed KEY_PRESSED can be swallowed, so no later character is ever lost.
     */
    static final Residue JUMP_RESIDUE = Residue.anyCharacter();

    private TabKeyboardShortcuts() {
    }

    /**
     * The jump slot of a key press: 1 to 9 for a jump chord, {@link #NOT_A_JUMP} otherwise. A digit
     * key that types Plus or Minus is the zoom key of its layout (the AZERTY 6 key types {@code -}),
     * so it stays with zooming.
     */
    static int slotOf(@NotNull KeyPress press) {
        int digit = digitOf(press.code());
        if (digit == NOT_A_JUMP || !hasJumpModifiers(press) || typesPlusOrMinus(press)) {
            return NOT_A_JUMP;
        }
        return digit;
    }

    /**
     * The tab index a slot selects among {@code tabCount} tabs: slots 1 to 8 their position, slot 9
     * the last tab, {@link #NO_TAB} when there is no tab at that position.
     */
    static int indexForSlot(int slot, int tabCount) {
        if (slot < 1 || slot > SLOT_COUNT || tabCount <= 0) {
            return NO_TAB;
        }
        if (slot == SLOT_COUNT) {
            return tabCount - 1;
        }
        return slot <= tabCount ? slot - 1 : NO_TAB;
    }

    /** {@link #slotOf} and {@link #indexForSlot} in one: {@link #NOT_A_JUMP} for a key that is no jump chord. */
    static int jumpIndex(@NotNull KeyPress press, int tabCount) {
        int slot = slotOf(press);
        return slot == NOT_A_JUMP ? NOT_A_JUMP : indexForSlot(slot, tabCount);
    }

    private static boolean hasJumpModifiers(KeyPress press) {
        if (press.macOs()) {
            return press.meta() && !press.ctrl() && !press.alt();
        }
        return press.ctrl() && !press.alt() && !press.shift() && !press.meta();
    }

    private static boolean typesPlusOrMinus(KeyPress press) {
        return "+".equals(press.text()) || "-".equals(press.text())
            || "+".equals(press.character()) || "-".equals(press.character());
    }

    private static int digitOf(KeyCode code) {
        return switch (code) {
            case DIGIT1, NUMPAD1 -> 1;
            case DIGIT2, NUMPAD2 -> 2;
            case DIGIT3, NUMPAD3 -> 3;
            case DIGIT4, NUMPAD4 -> 4;
            case DIGIT5, NUMPAD5 -> 5;
            case DIGIT6, NUMPAD6 -> 6;
            case DIGIT7, NUMPAD7 -> 7;
            case DIGIT8, NUMPAD8 -> 8;
            case DIGIT9, NUMPAD9 -> 9;
            default -> NOT_A_JUMP;
        };
    }
}
