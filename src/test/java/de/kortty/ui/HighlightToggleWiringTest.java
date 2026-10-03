package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

/**
 * Where Cmd/Ctrl+Shift+H and the highlighting menus are hooked into the main window and the terminal,
 * which no headless test can build: the chord goes through the scene shortcut router so it works with
 * the menu bar hidden and leaves no backspace behind, the toggle never trusts its own check mark, and
 * the pane's Highlighting submenu does not depend on the effect plugins.
 */
class HighlightToggleWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    /** The chord MainWindow declares, rebuilt here because the constant is private. */
    private static final KeyCombination TOGGLE =
        new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    @Test
    void theChordIsShortcutShiftH() throws IOException {
        String mainWindow = source("MainWindow.java");

        assertThat(mainWindow).contains("private static final KeyCombination HIGHLIGHTING_TOGGLE_ACCELERATOR =\n"
            + "        new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);");
        assertWithMessage("the View menu item shows the chord, so the accelerator uniqueness test sees it")
            .that(mainWindow).contains("toggle.setAccelerator(HIGHLIGHTING_TOGGLE_ACCELERATOR);");
    }

    @Test
    void theRouterHandlesTheChordWhileATerminalTabIsSelected() throws IOException {
        String router = region(source("MainWindow.java"), "private SceneShortcutRouter createSceneShortcutRouter() {",
            "\n    }\n");

        assertThat(router).contains(".consume(press -> press.matches(HIGHLIGHTING_TOGGLE_ACCELERATOR), terminalSelected,\n"
            + "                () -> toggleHighlightingInActiveTerminal(HighlightTelemetry.SOURCE_SHORTCUT), "
            + "Residue.ofLetter('H'))");
    }

    @Test
    void theToggleHandlersReadThePaneNotTheCheckMark() throws IOException {
        String mainWindow = source("MainWindow.java");
        String toggle = region(mainWindow, "private void toggleHighlightingInActiveTerminal(String source) {", "\n    }\n");
        String create = region(mainWindow, "private Menu createHighlightingMenu(MenuBarTarget target) {", "\n    }\n");

        assertThat(toggle).doesNotContain("isSelected");
        assertThat(toggle).contains("view.toggleHighlighting(view.getFocusedWidget(), source)");
        assertThat(toggle).contains("syncHighlightingToggleItems();");
        assertThat(create).doesNotContain("isSelected");
        assertThat(create).contains("menu.setOnShowing(");
    }

    @Test
    void cmdShiftHOnMacOsAndCtrlShiftHElsewhereButNeverPlainCtrlH() {
        assertThat(press(KeyCode.H, true, false, true, true).matches(TOGGLE)).isTrue();
        assertThat(press(KeyCode.H, true, true, false, false).matches(TOGGLE)).isTrue();
        // Plain Ctrl+H is the shell's backspace on Windows and Linux.
        assertThat(press(KeyCode.H, false, true, false, false).matches(TOGGLE)).isFalse();
        // Ctrl+Shift+H on macOS is not the Cmd chord.
        assertThat(press(KeyCode.H, true, true, false, true).matches(TOGGLE)).isFalse();
    }

    @Test
    void theChordLeavesNoBackspaceForTheShell() {
        List<String> ran = new ArrayList<>();
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(p -> p.matches(TOGGLE), SceneShortcutRouter.ALWAYS, () -> ran.add("toggle"), Residue.ofLetter('H'));

        KeyEvent chord = new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "\b", KeyCode.H,
            true, true, false, false);
        router.onKeyPressed(chord);
        KeyEvent residue = new KeyEvent(KeyEvent.KEY_TYPED, "\b", "", KeyCode.UNDEFINED, false, false, false, false);
        router.onKeyTyped(residue);

        assertThat(ran).containsExactly("toggle");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
    }

    @Test
    void thePaneSubmenuSitsOutsideTheEffectBlock() throws IOException {
        String factory = region(source("TerminalView.java"), "splitPane.setExtraMenuItemsFactory(widget -> {",
            "return items;");

        int highlight = factory.indexOf("buildPaneHighlightMenu(widget);");
        int effects = factory.indexOf("if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {");
        assertThat(highlight).isAtLeast(0);
        assertThat(effects).isAtLeast(0);
        assertWithMessage("the Highlighting submenu must show while terminal effects are switched off")
            .that(highlight).isLessThan(effects);
    }

    @Test
    void menuChoicesStayInTheRunningSession() throws IOException {
        String view = source("TerminalView.java");
        String apply = region(view, "private void applyHighlightChoice(", "\n    }\n");

        assertThat(apply).contains("setPaneHighlightOverride(pane, paneOverride);");
        assertThat(apply).contains("TelemetryEvents.TERMINAL_HIGHLIGHT_APPLIED");
        assertThat(apply).contains("HighlightTelemetry.props(after, source)");
        assertWithMessage("a menu choice is runtime-only: no connection or settings save")
            .that(apply).doesNotContainMatch("save|ServerConnection|GlobalSettings");
    }

    private static KeyPress press(KeyCode code, boolean shift, boolean ctrl, boolean meta, boolean macOs) {
        return new KeyPress(code, "", KeyEvent.CHAR_UNDEFINED, shift, ctrl, false, meta, macOs);
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
