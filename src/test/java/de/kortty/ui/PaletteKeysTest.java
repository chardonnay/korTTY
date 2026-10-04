package de.kortty.ui;

import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

/**
 * The command palette's chord, Cmd+Shift+P on macOS and Ctrl+Shift+P on Windows and Linux: which key
 * presses are the chord, which characters its KEY_TYPED can leave behind, and that while the palette
 * shows only the chord itself gets past the palette to the window, where the scene shortcut router
 * closes the palette and swallows the residue. Toolkit-free: key events are built directly and the
 * firewall and the router are called as the scenes would call them.
 */
class PaletteKeysTest {

    private static final boolean MAC = true;
    private static final boolean PC = false;
    private static final KeyCombination CHORD = MainWindow.commandPaletteAccelerator();

    @Test
    void theChordIsShortcutShiftP() {
        assertThat(isChord(PC, pressed(KeyCode.P, true, true, false, false))).isTrue();
        assertThat(isChord(MAC, pressed(KeyCode.P, true, false, false, true))).isTrue();
    }

    @Test
    void neighbouringKeysAreNotTheChord() {
        // AltGr+Shift+P arrives as Ctrl+Alt+Shift+P on Windows and types a character there.
        assertThat(isChord(PC, pressed(KeyCode.P, true, true, true, false))).isFalse();
        // Plain Ctrl+P is the shell's previous-history key; Shift+P types a P.
        assertThat(isChord(PC, pressed(KeyCode.P, false, true, false, false))).isFalse();
        assertThat(isChord(PC, pressed(KeyCode.P, true, false, false, false))).isFalse();
        assertThat(isChord(PC, pressed(KeyCode.O, true, true, false, false))).isFalse();
        // On macOS: the physical Ctrl key, Cmd+P (print) and Cmd+Option+Shift+P.
        assertThat(isChord(MAC, pressed(KeyCode.P, true, true, false, false))).isFalse();
        assertThat(isChord(MAC, pressed(KeyCode.P, false, false, false, true))).isFalse();
        assertThat(isChord(MAC, pressed(KeyCode.P, true, false, true, true))).isFalse();
    }

    @Test
    void theResidueIsTheLetterOrItsControlCharacter() {
        assertThat(PaletteKeys.RESIDUE.matches("\u0010")).isTrue();
        assertThat(PaletteKeys.RESIDUE.matches("p")).isTrue();
        assertThat(PaletteKeys.RESIDUE.matches("P")).isTrue();
        assertThat(PaletteKeys.RESIDUE.matches("o")).isFalse();
        assertThat(PaletteKeys.RESIDUE.matches("\u000f")).isFalse();
        assertThat(PaletteKeys.RESIDUE.matches("")).isFalse();
        assertThat(PaletteKeys.RESIDUE.matches(null)).isFalse();
    }

    @Test
    void whileThePaletteShowsOnlyTheChordsKeyPressGetsThrough() {
        for (boolean macOs : new boolean[] {PC, MAC}) {
            Predicate<KeyEvent> passThrough = PaletteKeys.passThrough(CHORD, macOs);
            QuickPickKeyFirewall firewall = new QuickPickKeyFirewall(passThrough, forward -> { }, () -> { });
            KeyEvent chord = pressed(KeyCode.P, true, !macOs, false, macOs);

            List<KeyEvent> stopped = List.of(
                pressed(KeyCode.D, false, true, false, false),
                pressed(KeyCode.L, false, true, false, false),
                pressed(KeyCode.PAGE_UP, false, false, false, false),
                pressed(KeyCode.F5, false, false, false, false),
                pressed(KeyCode.P, false, !macOs, false, macOs),
                pressed(KeyCode.P, true, true, true, false),
                typed("\u0010"),
                typed("P"),
                new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", KeyCode.P, true, !macOs, false, macOs));
            firewall.handle(chord);
            for (KeyEvent event : stopped) {
                firewall.handle(event);
            }

            assertThat(chord.isConsumed()).isFalse();
            for (KeyEvent event : stopped) {
                assertThat(event.isConsumed()).isTrue();
            }
        }
    }

    @Test
    void theRouterOpensThePaletteAndSwallowsTheResidueOnly() {
        List<String> toggles = new ArrayList<>();
        SceneShortcutRouter router = paletteRouter(PC, toggles);

        KeyEvent chord = pressed(KeyCode.P, true, true, false, false);
        router.onKeyPressed(chord);
        KeyEvent residue = typed("\u0010");
        router.onKeyTyped(residue);
        // A real P typed afterwards reaches the terminal.
        router.onKeyPressed(pressed(KeyCode.P, true, false, false, false));
        KeyEvent realP = typed("P");
        router.onKeyTyped(realP);

        assertThat(toggles).containsExactly("toggle");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
        assertThat(realP.isConsumed()).isFalse();
    }

    @Test
    void closingThePaletteWithTheChordLeavesNoCharacterBehind() {
        List<String> toggles = new ArrayList<>();
        SceneShortcutRouter router = paletteRouter(MAC, toggles);
        QuickPickKeyFirewall firewall =
            new QuickPickKeyFirewall(PaletteKeys.passThrough(CHORD, MAC), forward -> { }, () -> { });

        // The palette shows: the window hands the key to the popup first, and what the popup leaves
        // unconsumed goes on to the window's scene, where the router closes the palette.
        KeyEvent chord = pressed(KeyCode.P, true, false, false, true);
        firewall.handle(chord);
        if (!chord.isConsumed()) {
            router.onKeyPressed(chord);
        }
        // The palette is closed by now, so the KEY_TYPED goes to the window's scene directly.
        KeyEvent residue = typed("P");
        router.onKeyTyped(residue);

        assertThat(toggles).containsExactly("toggle");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
    }

    @Test
    void aChordWithoutKeyTypedDoesNotEatTheNextCharacter() {
        List<String> toggles = new ArrayList<>();
        SceneShortcutRouter router = paletteRouter(MAC, toggles);

        router.onKeyPressed(pressed(KeyCode.P, true, false, false, true));
        // macOS delivered no KEY_TYPED for Cmd+Shift+P; the next key is a plain p.
        router.onKeyPressed(pressed(KeyCode.P, false, false, false, false));
        KeyEvent p = typed("p");
        router.onKeyTyped(p);

        assertThat(p.isConsumed()).isFalse();
    }

    /** The router entry MainWindow registers, with the palette toggle recorded instead of performed. */
    private static SceneShortcutRouter paletteRouter(boolean macOs, List<String> toggles) {
        return new SceneShortcutRouter(macOs)
            .consume(press -> PaletteKeys.isChord(press, CHORD), SceneShortcutRouter.ALWAYS,
                () -> toggles.add("toggle"), PaletteKeys.RESIDUE);
    }

    private static boolean isChord(boolean macOs, KeyEvent event) {
        return PaletteKeys.isChord(KeyPress.of(event, macOs), CHORD)
            && PaletteKeys.passThrough(CHORD, macOs).test(event);
    }

    private static KeyEvent pressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, ctrl, alt, meta);
    }

    private static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }
}
