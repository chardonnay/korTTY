package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * File &gt; Reopen Closed Tab is Cmd+Opt+Shift+T on macOS and Ctrl+Alt+Shift+T on Windows and Linux:
 * the browsers' Shortcut+Shift+T is the command-timestamps toggle and Shortcut+Alt+T the session
 * journal. The chord goes through the main window's scene shortcut router, so it works while a
 * terminal has the focus and with the menu bar hidden, and whatever character AltGr+Shift+T types on
 * a Windows layout is swallowed with it. Toolkit-free: the router entry is rebuilt here from the same
 * combination, which a source pin keeps in step with MainWindow, and key events are built directly.
 */
class ReopenClosedTabShortcutTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final boolean MAC = true;
    private static final boolean PC = false;

    /** MainWindow.REOPEN_CLOSED_TAB_ACCELERATOR; {@link #mainWindowRoutesTheReopenChord()} pins the two together. */
    private static final KeyCombination REOPEN = new KeyCodeCombination(
        KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN);

    @Test
    void mainWindowRoutesTheReopenChord() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(Pattern.compile("static final KeyCombination REOPEN_CLOSED_TAB_ACCELERATOR\\s*=\\s*"
                + "new KeyCodeCombination\\(\\s*KeyCode\\.T,\\s*KeyCombination\\.SHORTCUT_DOWN,\\s*"
                + "KeyCombination\\.ALT_DOWN,\\s*KeyCombination\\.SHIFT_DOWN\\s*\\)").matcher(source).find()).isTrue();
        assertThat(source).contains("reopenClosedTab.setAccelerator(REOPEN_CLOSED_TAB_ACCELERATOR);");

        int routerStart = source.indexOf("private SceneShortcutRouter createSceneShortcutRouter() {");
        assertThat(routerStart).isAtLeast(0);
        int routerEnd = source.indexOf("return router;", routerStart);
        assertThat(routerEnd).isGreaterThan(routerStart);
        Matcher entry = Pattern.compile("\\.consume\\(press -> press\\.matches\\(REOPEN_CLOSED_TAB_ACCELERATOR\\), "
                + "SceneShortcutRouter\\.ALWAYS,\\s*\\(\\) -> Platform\\.runLater\\(this::reopenClosedTab\\), "
                + "Residue\\.anyCharacter\\(\\)\\)")
            .matcher(source);
        assertThat(entry.find(routerStart)).isTrue();
        assertThat(entry.start()).isLessThan(routerEnd);
    }

    @Test
    void theFileMenuAndTheTabMenuOfferReopenAndRefreshItAsTheyOpen() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        String fileMenu = methodBody(source, "private Menu createFileMenu() {");
        assertThat(fileMenu).contains("I18n.get(\"menu.file.reopenClosedTab\")");
        assertThat(fileMenu).contains("I18n.get(\"menu.file.recentlyClosed\")");
        assertThat(fileMenu).contains("closeAllTabs,\n            reopenClosedTab, recentlyClosed, new SeparatorMenuItem(),");
        assertThat(fileMenu.substring(fileMenu.indexOf("fileMenu.setOnShowing("))).contains("syncRecentlyClosedMenus();");

        String tabMenu = methodBody(source, "private void setupTabContextMenu(TerminalTab terminalTab) {");
        assertThat(tabMenu).contains("I18n.get(\"tab.contextMenu.reopenClosed\")");
        assertThat(tabMenu.substring(tabMenu.indexOf("contextMenu.setOnShowing(e -> {")))
            .contains("reopenClosedItem.setDisable(closedTabHistory.isEmpty());");

        String reopen = methodBody(source, "private void reopenClosedTab() {");
        // The key also works with the menu bar hidden, where a greyed-out item tells nobody anything.
        assertThat(reopen).contains("updateStatus(I18n.get(\"status.noClosedTab\"));");

        String items = methodBody(source, "private List<MenuItem> recentlyClosedMenuItems() {");
        assertThat(items).contains("item.setMnemonicParsing(false);");
        assertThat(items).contains("I18n.get(\"menu.file.recentlyClosed.empty\")");
        assertThat(items).contains("I18n.get(\"menu.file.recentlyClosed.clear\")");
    }

    @Test
    void ctrlAltShiftTReopensOnWindowsAndLinuxAndWhatAltGrTypesIsSwallowed() {
        List<String> reopened = new ArrayList<>();
        SceneShortcutRouter router = reopenRouter(PC, reopened);

        KeyEvent chord = keyPressed(KeyCode.T, "t", true, true, true, false);
        router.onKeyPressed(chord);
        // AltGr+Shift+T types a character on some layouts (Ŧ on Croatian and Polish ones).
        KeyEvent residue = keyTyped("Ŧ");
        router.onKeyTyped(residue);
        // The next character is a real one and reaches the shell.
        KeyEvent next = keyTyped("t");
        router.onKeyTyped(next);

        assertThat(reopened).containsExactly("reopen");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void cmdOptShiftTReopensOnMacOs() {
        List<String> reopened = new ArrayList<>();
        SceneShortcutRouter router = reopenRouter(MAC, reopened);

        KeyEvent chord = keyPressed(KeyCode.T, "t", true, false, true, true);
        router.onKeyPressed(chord);
        // Without a typed character the next key press disarms the guard, so nothing is lost.
        KeyEvent nextPress = keyPressed(KeyCode.A, "a", false, false, false, false);
        router.onKeyPressed(nextPress);
        KeyEvent next = keyTyped("a");
        router.onKeyTyped(next);

        assertThat(reopened).containsExactly("reopen");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(nextPress.isConsumed()).isFalse();
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void neighbouringChordsAreNotReopen() {
        // Windows and Linux: New Tab, the timestamps toggle and the session journal toggle.
        assertNotReopen(PC, keyPressed(KeyCode.T, "\u0014", false, true, false, false), "\u0014");
        assertNotReopen(PC, keyPressed(KeyCode.T, "\u0014", true, true, false, false), "\u0014");
        assertNotReopen(PC, keyPressed(KeyCode.T, "\u0014", false, true, true, false), "\u0014");
        // macOS: the same three with Cmd, and the physical Ctrl key with Opt+Shift.
        assertNotReopen(MAC, keyPressed(KeyCode.T, "t", false, false, false, true), "t");
        assertNotReopen(MAC, keyPressed(KeyCode.T, "t", true, false, false, true), "T");
        assertNotReopen(MAC, keyPressed(KeyCode.T, "t", false, false, true, true), "t");
        assertNotReopen(MAC, keyPressed(KeyCode.T, "t", true, true, true, false), "T");
    }

    private static void assertNotReopen(boolean macOs, KeyEvent press, String typedCharacter) {
        List<String> reopened = new ArrayList<>();
        SceneShortcutRouter router = reopenRouter(macOs, reopened);

        router.onKeyPressed(press);
        KeyEvent typed = keyTyped(typedCharacter);
        router.onKeyTyped(typed);

        assertThat(reopened).isEmpty();
        assertThat(press.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    /** The router entry MainWindow registers, with the reopen recorded instead of performed. */
    private static SceneShortcutRouter reopenRouter(boolean macOs, List<String> reopened) {
        return new SceneShortcutRouter(macOs)
            .consume(press -> press.matches(REOPEN), SceneShortcutRouter.ALWAYS,
                () -> reopened.add("reopen"), Residue.anyCharacter());
    }

    private static KeyEvent keyPressed(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt,
                                       boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
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
}
