package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How the command palette's terminal and tab commands reach the selected terminal tab, pinned
 * against the source because neither MainWindow nor a terminal tab can be built without a stage:
 * MainWindow hands them the selected terminal tab (nothing in other tabs) after the menu commands,
 * so a menu item with the same id would win, and shows the terminal's own Clear Buffer key; the tab
 * adapter goes to the focused pane's Clear Buffer, which takes the same path as the terminal's
 * right-click menu, and to the tab's own Duplicate and Reconnect. The splits and broadcast mode are
 * no commands of the adapter: View → Panes has them and the palette harvests that menu.
 */
class TerminalPaletteWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path TARGET = Path.of("src/main/java/de/kortty/ui/TerminalPaletteTarget.java");
    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    @Test
    void theWindowHandsTheSelectedTerminalTabToTheCommandsAfterTheMenus() throws IOException {
        String registry = methodBody(source(MAIN_WINDOW), "private ActionRegistry actionRegistry() {");

        assertThat(registry).contains("TerminalView.clearBufferActionPresentation(isMacOs()).getKeyCombinations();");
        assertThat(registry).contains("TerminalPaletteActions.actions(\n"
            + "                () -> tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab\n"
            + "                    ? new TerminalPaletteTarget(terminalTab, this::duplicateTab) : null,\n"
            + "                I18n::get, clearBufferChords.isEmpty() ? null : clearBufferChords.get(0));");
        assertThat(registry).contains("registry.addContributor(() -> terminalActions);");
        assertWithMessage("the harvested menu commands come first and win an id clash")
            .that(registry.indexOf("registry.addContributor(() -> terminalActions);"))
            .isGreaterThan(registry.indexOf("MenuActionHarvester.harvest(menuBar.getMenus())"));
    }

    @Test
    void theTabAdapterUsesTheFocusedPaneAndTheTabsOwnCommands() throws IOException {
        String target = source(TARGET);

        assertThat(methodBody(target, "public void clearBuffer() {"))
            .contains("tab.getTerminalView().clearFocusedBuffer();");
        assertThat(methodBody(target, "public void duplicate() {")).contains("duplicate.accept(tab);");
        assertThat(methodBody(target, "public void reconnect() {")).contains("tab.triggerReconnect();");
        for (String paneCommand : List.of("splitFocused", "toggleBroadcast", "isBroadcastMode", "paneCount")) {
            assertWithMessage("View → Panes splits and switches broadcast mode; the palette lists that menu")
                .that(target).doesNotContain(paneCommand);
        }
    }

    @Test
    void clearBufferTakesTheRightClickMenusPath() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(methodBody(view, "public void clearFocusedBuffer() {"))
            .contains("if (getFocusedWidget() instanceof TerminalPaneActions actions) {\n"
                + "            actions.clearBuffer();");
        assertWithMessage("the palette's split is View → Panes → Split Right / Split Down (splitFocusedPane)")
            .that(view).doesNotContain("public boolean splitFocused(Orientation orientation) {");
        assertWithMessage("the palette's broadcast toggle is View → Panes → Broadcast to All Panes of This Tab")
            .that(view).doesNotContain("public void toggleBroadcast() {");
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
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
}
