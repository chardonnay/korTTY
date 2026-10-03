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
 * focus between the panes of the selected terminal tab, Cmd/Ctrl+Shift+O splits its focused pane,
 * Cmd/Ctrl+Shift+Enter zooms it, and nothing else does: Ctrl+L, Ctrl+F, Ctrl+O, Enter with Shift, Ctrl
 * or Alt alone and the arrow keys with Alt, Ctrl, Ctrl+Shift or Option alone keep reaching the shell.
 * The chords go through the main window's scene shortcut router only while the keyboard is in that
 * tab and, for the focus and zoom keys, it has two or more panes. Toolkit-free: the chords are rebuilt
 * here from the same combinations, which source pins keep in step with MainWindow, and key events are
 * built directly.
 */
class PaneShortcutsTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final boolean MAC = true;
    private static final boolean PC = false;

    /** MainWindow.PANE_SPLIT_ACCELERATOR. */
    private static final KeyCombination SPLIT_CHORD =
        new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    /** MainWindow.PANE_ZOOM_ACCELERATOR. */
    private static final KeyCombination ZOOM_CHORD =
        new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    /**
     * MainWindow.PANE_FOCUS_*_ACCELERATOR, PANE_SPLIT_ACCELERATOR and PANE_ZOOM_ACCELERATOR;
     * {@link #mainWindowDeclaresTheChordsAndShowsThemInViewPanes()} pins them.
     */
    private static final PaneShortcuts SHORTCUTS = new PaneShortcuts(Map.of(
        PaneAction.FOCUS_LEFT, chord(KeyCode.LEFT),
        PaneAction.FOCUS_RIGHT, chord(KeyCode.RIGHT),
        PaneAction.FOCUS_UP, chord(KeyCode.UP),
        PaneAction.FOCUS_DOWN, chord(KeyCode.DOWN),
        PaneAction.SPLIT, SPLIT_CHORD,
        PaneAction.ZOOM, ZOOM_CHORD));

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
        assertWithMessage("PANE_SPLIT_ACCELERATOR").that(Pattern.compile("static final KeyCombination "
                + "PANE_SPLIT_ACCELERATOR\\s*=\\s*new KeyCodeCombination\\(\\s*KeyCode\\.O,\\s*"
                + "KeyCombination\\.SHORTCUT_DOWN,\\s*KeyCombination\\.SHIFT_DOWN\\s*\\)")
            .matcher(source).find()).isTrue();
        assertThat(source).contains("PaneShortcuts.PaneAction.SPLIT, PANE_SPLIT_ACCELERATOR,");
        assertThat(source).contains("panes.split().setAccelerator(PANE_SPLIT_ACCELERATOR);");
        assertWithMessage("PANE_ZOOM_ACCELERATOR").that(Pattern.compile("static final KeyCombination "
                + "PANE_ZOOM_ACCELERATOR\\s*=\\s*new KeyCodeCombination\\(\\s*KeyCode\\.ENTER,\\s*"
                + "KeyCombination\\.SHORTCUT_DOWN,\\s*KeyCombination\\.SHIFT_DOWN\\s*\\)")
            .matcher(source).find()).isTrue();
        assertThat(source).contains("PaneShortcuts.PaneAction.ZOOM, PANE_ZOOM_ACCELERATOR));");
        assertThat(source).contains("panes.zoom().setAccelerator(PANE_ZOOM_ACCELERATOR);");
    }

    @Test
    void mainWindowRoutesEveryPaneChordOnlyWhileTheKeyboardIsInATerminalTab() throws IOException {
        String source = source();
        int routerStart = source.indexOf("private SceneShortcutRouter createSceneShortcutRouter() {");
        assertThat(routerStart).isAtLeast(0);
        int routerEnd = source.indexOf("return router;", routerStart);
        String router = source.substring(routerStart, routerEnd);

        assertThat(router).contains("for (PaneShortcuts.PaneAction paneAction : PaneShortcuts.PaneAction.values()) {");
        assertThat(router).contains("router.consume(press -> PANE_SHORTCUTS.isChordOf(press, paneAction),\n"
            + "                () -> isKeyboardInSelectedTerminal() && PaneShortcuts.applies(paneAction, activeTerminalPaneCount()),\n"
            + "                () -> runPaneShortcut(paneAction), paneAction.residue());");
        assertWithMessage("the split's connect dialog runs a nested event loop: after the key event, not inside it")
            .that(methodBody(source, "private void runPaneShortcut(PaneShortcuts.PaneAction action) {"))
            .contains("case SPLIT -> Platform.runLater(() -> splitPaneInActiveTerminal(null));");
        assertThat(methodBody(source, "private void runPaneShortcut(PaneShortcuts.PaneAction action) {"))
            .contains("case ZOOM -> toggleZoomInActiveTerminal();");
    }

    @Test
    void cmdShiftEnterZoomsOnMacOsAndCtrlShiftEnterOnWindowsAndLinux() {
        assertThat(SHORTCUTS.match(press(KeyCode.ENTER, true, false, false, true, MAC))).hasValue(PaneAction.ZOOM);
        assertThat(SHORTCUTS.match(press(KeyCode.ENTER, true, true, false, false, PC))).hasValue(PaneAction.ZOOM);
        assertThat(SHORTCUTS.chordOf(PaneAction.ZOOM)).isEqualTo(ZOOM_CHORD);
        assertThat(PaneAction.ZOOM.direction()).isNull();
    }

    @Test
    void neighbouringEnterKeysNeverZoom() {
        for (boolean macOs : List.of(MAC, PC)) {
            // Enter runs the command line; Shift+Enter, Ctrl+Enter and Alt+Enter are keys programs use.
            assertNoPaneChord(press(KeyCode.ENTER, false, false, false, false, macOs));
            assertNoPaneChord(press(KeyCode.ENTER, true, false, false, false, macOs));
            assertNoPaneChord(press(KeyCode.ENTER, false, true, false, false, macOs));
            assertNoPaneChord(press(KeyCode.ENTER, false, false, true, false, macOs));
            // Ctrl+Alt+Shift+Enter can be AltGr+Shift+Enter on Windows.
            assertNoPaneChord(press(KeyCode.ENTER, true, true, true, false, macOs));
        }
        // Cmd+Enter alone, and the physical Ctrl key, which is no Cmd, on macOS.
        assertNoPaneChord(press(KeyCode.ENTER, false, false, false, true, MAC));
        assertNoPaneChord(press(KeyCode.ENTER, true, true, false, false, MAC));
        assertNoPaneChord(press(KeyCode.ENTER, true, false, true, true, MAC));
    }

    @Test
    void theZoomChordSwallowsTheCarriageReturnOrLineFeedItCanStillType() {
        Residue zoom = PaneAction.ZOOM.residue();
        assertWithMessage("Enter types a carriage return").that(zoom.matches("\r")).isTrue();
        assertWithMessage("Ctrl turns Enter into a line feed on Windows").that(zoom.matches("\n")).isTrue();
        assertThat(zoom.matches("x")).isFalse();
        assertThat(zoom.matches(" ")).isFalse();
    }

    @Test
    void theZoomShortcutNeedsASecondPane() {
        assertThat(PaneShortcuts.applies(PaneAction.ZOOM, 0)).isFalse();
        assertWithMessage("with one pane the shell gets the key as Enter")
            .that(PaneShortcuts.applies(PaneAction.ZOOM, 1)).isFalse();
        assertThat(PaneShortcuts.applies(PaneAction.ZOOM, 2)).isTrue();
        assertThat(PaneShortcuts.applies(PaneAction.ZOOM, 4)).isTrue();
    }

    @Test
    void ctrlShiftEnterZoomsASplitTabAndNoEnterReachesTheShell() {
        Routed routed = new Routed(PC, 3, true);

        KeyEvent chord = keyPressed(KeyCode.ENTER, true, true, false, false);
        routed.router.onKeyPressed(chord);
        KeyEvent residue = keyTyped("\n");
        routed.router.onKeyTyped(residue);
        KeyEvent next = keyTyped("l");
        routed.router.onKeyTyped(next);

        assertThat(routed.ran).containsExactly(PaneAction.ZOOM);
        assertThat(chord.isConsumed()).isTrue();
        assertWithMessage("a line feed would run the command line").that(residue.isConsumed()).isTrue();
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void withOnePaneCmdShiftEnterReachesTheShell() {
        Routed routed = new Routed(MAC, 1, true);

        KeyEvent chord = keyPressed(KeyCode.ENTER, true, false, false, true);
        routed.router.onKeyPressed(chord);

        assertThat(routed.ran).isEmpty();
        assertThat(chord.isConsumed()).isFalse();
    }

    @Test
    void cmdShiftOSplitsOnMacOsAndCtrlShiftOOnWindowsAndLinux() {
        assertThat(SHORTCUTS.match(press(KeyCode.O, true, false, false, true, MAC))).hasValue(PaneAction.SPLIT);
        assertThat(SHORTCUTS.match(press(KeyCode.O, true, true, false, false, PC))).hasValue(PaneAction.SPLIT);
        assertThat(SHORTCUTS.chordOf(PaneAction.SPLIT)).isEqualTo(SPLIT_CHORD);
    }

    @Test
    void neighbouringOKeysNeverSplit() {
        // Ctrl+O saves in nano and runs the next history line in bash; Cmd/Ctrl+O opens a project.
        assertNoPaneChord(press(KeyCode.O, false, true, false, false, PC));
        assertNoPaneChord(press(KeyCode.O, false, false, false, true, MAC));
        // Ctrl+Alt+Shift+O can be AltGr+Shift+O, which types a character on some layouts.
        assertNoPaneChord(press(KeyCode.O, true, true, true, false, PC));
        assertNoPaneChord(press(KeyCode.O, true, false, true, true, MAC));
        // The physical Ctrl key is no Cmd, and Shift+O is a capital O.
        assertNoPaneChord(press(KeyCode.O, true, true, false, false, MAC));
        assertNoPaneChord(press(KeyCode.O, true, false, false, false, PC));
        assertNoPaneChord(press(KeyCode.O, true, false, false, false, MAC));
    }

    @Test
    void theSplitChordSwallowsTheLetterItCanStillTypeTheArrowsNothing() {
        Residue split = PaneAction.SPLIT.residue();
        assertThat(split.matches("o")).isTrue();
        assertThat(split.matches("O")).isTrue();
        assertWithMessage("Ctrl turns O into U+000F").that(split.matches("\u000F")).isTrue();
        assertThat(split.matches("p")).isFalse();
        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(PaneAction.focus(direction).residue()).isEqualTo(Residue.NONE);
        }
        assertThat(PaneAction.SPLIT.direction()).isNull();
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
    void aFocusShortcutNeedsASecondPane() {
        for (PaneDirection direction : PaneDirection.values()) {
            PaneAction action = PaneAction.focus(direction);
            assertThat(PaneShortcuts.applies(action, 0)).isFalse();
            assertThat(PaneShortcuts.applies(action, 1)).isFalse();
            assertThat(PaneShortcuts.applies(action, 2)).isTrue();
            assertThat(PaneShortcuts.applies(action, 5)).isTrue();
        }
    }

    @Test
    void theSplitShortcutNeedsOnlyAPaneToSplit() {
        assertThat(PaneShortcuts.applies(PaneAction.SPLIT, 0)).isFalse();
        assertThat(PaneShortcuts.applies(PaneAction.SPLIT, 1)).isTrue();
        assertThat(PaneShortcuts.applies(PaneAction.SPLIT, 4)).isTrue();
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
    void ctrlShiftOSplitsAOnePaneTabAndSwallowsItsControlCharacter() {
        Routed routed = new Routed(PC, 1, true);

        KeyEvent chord = keyPressed(KeyCode.O, true, true, false, false);
        routed.router.onKeyPressed(chord);
        KeyEvent residue = keyTyped("\u000F");
        routed.router.onKeyTyped(residue);
        KeyEvent next = keyTyped("o");
        routed.router.onKeyTyped(next);

        assertThat(routed.ran).containsExactly(PaneAction.SPLIT);
        assertThat(chord.isConsumed()).isTrue();
        assertWithMessage("Ctrl+O must not reach the shell as well").that(residue.isConsumed()).isTrue();
        assertWithMessage("only the first KEY_TYPED is the chord's").that(next.isConsumed()).isFalse();
    }

    @Test
    void withTheKeyboardOutsideTheTerminalTabTheSplitChordIsLeftAlone() {
        Routed routed = new Routed(MAC, 2, false);

        KeyEvent chord = keyPressed(KeyCode.O, true, false, false, true);
        routed.router.onKeyPressed(chord);

        assertThat(routed.ran).isEmpty();
        assertWithMessage("an editor tab keeps Cmd+Shift+O").that(chord.isConsumed()).isFalse();
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
                    () -> ran.add(paneAction), paneAction.residue());
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

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
