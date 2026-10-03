package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.PaneShortcuts.PaneAction;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Cmd+Option with an arrow key on macOS and Ctrl+Alt with an arrow key on Windows and Linux move the
 * focus between the panes of the selected terminal tab, and nothing else does: Ctrl+L, Ctrl+F and the
 * arrow keys with Alt, Ctrl, Ctrl+Shift or Option alone keep reaching the shell. The chords go
 * through the main window's scene shortcut router only while the keyboard is in that tab and it has
 * two or more panes. Toolkit-free: the chords are rebuilt here from the same combinations, which
 * source pins keep in step with MainWindow, and key events are built directly.
 */
class PaneShortcutsTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final boolean MAC = true;
    private static final boolean PC = false;

    /** MainWindow.PANE_FOCUS_*_ACCELERATOR; {@link #mainWindowDeclaresTheChordsAndShowsThemInViewPanes()} pins them. */
    private static final PaneShortcuts SHORTCUTS = new PaneShortcuts(Map.of(
        PaneAction.FOCUS_LEFT, chord(KeyCode.LEFT),
        PaneAction.FOCUS_RIGHT, chord(KeyCode.RIGHT),
        PaneAction.FOCUS_UP, chord(KeyCode.UP),
        PaneAction.FOCUS_DOWN, chord(KeyCode.DOWN)));

    private static KeyCombination chord(KeyCode arrow) {
        return new KeyCodeCombination(arrow, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    }

    @Test
    void mainWindowDeclaresTheChordsAndShowsThemInViewPanes() throws IOException {
        String source = source();
        for (String direction : List.of("LEFT", "RIGHT", "UP", "DOWN")) {
            String constant = "PANE_FOCUS_" + direction + "_ACCELERATOR";
            assertWithMessage(constant).that(Pattern.compile("static final KeyCombination " + constant + "\\s*=\\s*"
                    + "new KeyCodeCombination\\(\\s*KeyCode\\." + direction + ",\\s*KeyCombination\\.SHORTCUT_DOWN,\\s*"
                    + "KeyCombination\\.ALT_DOWN\\s*\\)").matcher(source).find()).isTrue();
            assertThat(source).contains("PaneShortcuts.PaneAction.FOCUS_" + direction + ", " + constant);
            assertThat(source).contains("panes.focusItem(PaneNavigator.PaneDirection." + direction
                + ").setAccelerator(" + constant + ");");
        }
        assertThat(source).contains("private static final PaneShortcuts PANE_SHORTCUTS = new PaneShortcuts(Map.of(");
    }

    @Test
    void mainWindowRoutesEveryPaneChordOnlyInATerminalTabWithSeveralPanes() throws IOException {
        String source = source();
        int routerStart = source.indexOf("private SceneShortcutRouter createSceneShortcutRouter() {");
        assertThat(routerStart).isAtLeast(0);
        int routerEnd = source.indexOf("return router;", routerStart);
        String router = source.substring(routerStart, routerEnd);

        assertThat(router).contains("for (PaneShortcuts.PaneAction paneAction : PaneShortcuts.PaneAction.values()) {");
        assertThat(router).contains("router.consume(press -> PANE_SHORTCUTS.isChordOf(press, paneAction),\n"
            + "                () -> isKeyboardInSelectedTerminal() && PaneShortcuts.applies(paneAction, activeTerminalPaneCount()),\n"
            + "                () -> runPaneShortcut(paneAction), Residue.NONE);");
    }

    @Test
    void cmdOptionArrowsMoveTheFocusOnMacOs() {
        assertThat(SHORTCUTS.match(press(KeyCode.LEFT, false, false, true, true, MAC))).hasValue(PaneAction.FOCUS_LEFT);
        assertThat(SHORTCUTS.match(press(KeyCode.RIGHT, false, false, true, true, MAC))).hasValue(PaneAction.FOCUS_RIGHT);
        assertThat(SHORTCUTS.match(press(KeyCode.UP, false, false, true, true, MAC))).hasValue(PaneAction.FOCUS_UP);
        assertThat(SHORTCUTS.match(press(KeyCode.DOWN, false, false, true, true, MAC))).hasValue(PaneAction.FOCUS_DOWN);
    }

    @Test
    void ctrlAltArrowsMoveTheFocusOnWindowsAndLinux() {
        assertThat(SHORTCUTS.match(press(KeyCode.LEFT, false, true, true, false, PC))).hasValue(PaneAction.FOCUS_LEFT);
        assertThat(SHORTCUTS.match(press(KeyCode.RIGHT, false, true, true, false, PC))).hasValue(PaneAction.FOCUS_RIGHT);
        assertThat(SHORTCUTS.match(press(KeyCode.UP, false, true, true, false, PC))).hasValue(PaneAction.FOCUS_UP);
        assertThat(SHORTCUTS.match(press(KeyCode.DOWN, false, true, true, false, PC))).hasValue(PaneAction.FOCUS_DOWN);
    }

    @Test
    void shellKeysNeverMatchOnWindowsAndLinux() {
        // Ctrl+L redraws and Ctrl+F moves forward in the shell; Ctrl+D is EOF; Ctrl+Tab switches tabs.
        assertNoPaneChord(press(KeyCode.L, false, true, false, false, PC));
        assertNoPaneChord(press(KeyCode.F, false, true, false, false, PC));
        assertNoPaneChord(press(KeyCode.D, false, true, false, false, PC));
        assertNoPaneChord(press(KeyCode.TAB, false, true, false, false, PC));
        for (KeyCode arrow : List.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.UP, KeyCode.DOWN)) {
            assertNoPaneChord(press(arrow, false, false, false, false, PC));
            // Alt+arrow, Ctrl+arrow (word moves, scrollback) and Ctrl+Shift+arrow (selection) stay xterm keys.
            assertNoPaneChord(press(arrow, false, false, true, false, PC));
            assertNoPaneChord(press(arrow, false, true, false, false, PC));
            assertNoPaneChord(press(arrow, true, true, false, false, PC));
            assertNoPaneChord(press(arrow, true, false, false, false, PC));
            assertNoPaneChord(press(arrow, true, true, true, false, PC));
            assertNoPaneChord(press(arrow, false, true, true, true, PC));
        }
    }

    @Test
    void neighbouringKeysNeverMatchOnMacOs() {
        for (KeyCode arrow : List.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.UP, KeyCode.DOWN)) {
            // Option+arrow moves a word, Cmd+arrow scrolls or goes to the line's ends.
            assertNoPaneChord(press(arrow, false, false, true, false, MAC));
            assertNoPaneChord(press(arrow, false, false, false, true, MAC));
            // The physical Ctrl key is no Cmd.
            assertNoPaneChord(press(arrow, false, true, true, false, MAC));
            assertNoPaneChord(press(arrow, true, false, true, true, MAC));
            assertNoPaneChord(press(arrow, false, true, true, true, MAC));
        }
        assertNoPaneChord(press(KeyCode.L, false, false, true, true, MAC));
    }

    @Test
    void aPaneShortcutNeedsASecondPane() {
        for (PaneAction action : PaneAction.values()) {
            assertThat(PaneShortcuts.applies(action, 0)).isFalse();
            assertThat(PaneShortcuts.applies(action, 1)).isFalse();
            assertThat(PaneShortcuts.applies(action, 2)).isTrue();
            assertThat(PaneShortcuts.applies(action, 5)).isTrue();
        }
    }

    @Test
    void everyFocusActionMovesInItsOwnDirection() {
        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(PaneAction.focus(direction).direction()).isEqualTo(direction);
        }
        assertThat(SHORTCUTS.chordOf(PaneAction.FOCUS_UP)).isEqualTo(chord(KeyCode.UP));
    }

    @Test
    void withSeveralPanesTheRouterTakesTheChordBeforeTheTerminalEncodesIt() {
        Routed routed = new Routed(PC, 2, true);

        KeyEvent chord = keyPressed(KeyCode.RIGHT, false, true, true, false);
        routed.router.onKeyPressed(chord);
        // The next real character is typed into the shell, never swallowed.
        KeyEvent next = keyTyped("x");
        routed.router.onKeyTyped(next);

        assertThat(routed.ran).containsExactly(PaneAction.FOCUS_RIGHT);
        assertThat(chord.isConsumed()).isTrue();
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void withOnePaneTheChordReachesTheShell() {
        Routed routed = new Routed(PC, 1, true);

        KeyEvent chord = keyPressed(KeyCode.LEFT, false, true, true, false);
        routed.router.onKeyPressed(chord);

        assertThat(routed.ran).isEmpty();
        assertWithMessage("the terminal encodes Ctrl+Alt+Left as xterm does").that(chord.isConsumed()).isFalse();
    }

    @Test
    void withTheKeyboardOutsideTheTerminalTabTheChordIsLeftAlone() {
        Routed routed = new Routed(MAC, 3, false);

        KeyEvent chord = keyPressed(KeyCode.UP, false, false, true, true);
        routed.router.onKeyPressed(chord);

        assertThat(routed.ran).isEmpty();
        assertThat(chord.isConsumed()).isFalse();
    }

    @Test
    void ctrlLIsNeverTakenEvenWithSeveralPanes() {
        Routed routed = new Routed(PC, 4, true);

        KeyEvent ctrlL = keyPressed(KeyCode.L, false, true, false, false);
        routed.router.onKeyPressed(ctrlL);

        assertThat(routed.ran).isEmpty();
        assertThat(ctrlL.isConsumed()).isFalse();
    }

    private static void assertNoPaneChord(KeyPress press) {
        assertWithMessage("%s", press).that(SHORTCUTS.match(press)).isEmpty();
        for (PaneAction action : PaneAction.values()) {
            assertThat(SHORTCUTS.isChordOf(press, action)).isFalse();
        }
    }

    /** The router entries MainWindow registers, with the action recorded instead of performed. */
    private static final class Routed {
        final List<PaneAction> ran = new ArrayList<>();
        final SceneShortcutRouter router;

        Routed(boolean macOs, int paneCount, boolean keyboardInTerminal) {
            router = new SceneShortcutRouter(macOs);
            for (PaneAction paneAction : PaneAction.values()) {
                router.consume(press -> SHORTCUTS.isChordOf(press, paneAction),
                    () -> keyboardInTerminal && PaneShortcuts.applies(paneAction, paneCount),
                    () -> ran.add(paneAction), Residue.NONE);
            }
        }
    }

    private static KeyPress press(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta, boolean macOs) {
        return new KeyPress(code, "", "", shift, ctrl, alt, meta, macOs);
    }

    private static KeyEvent keyPressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
