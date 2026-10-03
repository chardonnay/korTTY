package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How the command palette's terminal and tab commands reach the selected terminal tab, pinned
 * against the source because neither MainWindow nor a terminal tab can be built without a stage:
 * MainWindow hands them the selected terminal tab (nothing in other tabs) after the menu commands,
 * so a menu item with the same id would win, and shows the terminal's own Clear Buffer key; the tab
 * adapter goes to the focused pane's commands and the tab's own Duplicate and Reconnect; and the
 * focused-pane commands of TerminalView take the same paths as the terminal's right-click menu,
 * the same-server split through the split connector factory with its dialogs.
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
        assertThat(methodBody(target, "public void splitRight() {"))
            .contains("tab.getTerminalView().splitFocused(Orientation.HORIZONTAL);");
        assertThat(methodBody(target, "public void splitDown() {"))
            .contains("tab.getTerminalView().splitFocused(Orientation.VERTICAL);");
        assertThat(methodBody(target, "public int paneCount() {"))
            .contains("tab.getTerminalView().getTerminalPaneCount();");
        assertThat(methodBody(target, "public boolean isBroadcasting() {"))
            .contains("tab.getTerminalView().isBroadcastMode();");
        assertThat(methodBody(target, "public void toggleBroadcast() {"))
            .contains("tab.getTerminalView().toggleBroadcast();");
        assertThat(methodBody(target, "public void duplicate() {")).contains("duplicate.accept(tab);");
        assertThat(methodBody(target, "public void reconnect() {")).contains("tab.triggerReconnect();");
    }

    @Test
    void theFocusedPaneCommandsTakeTheRightClickMenusPaths() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(methodBody(view, "public void clearFocusedBuffer() {"))
            .contains("if (getFocusedWidget() instanceof TerminalPaneActions actions) {\n"
                + "            actions.clearBuffer();");
        String split = methodBody(view, "public boolean splitFocused(Orientation orientation) {");
        assertThat(split).contains("SithTermFxWidget focused = getFocusedWidget();");
        assertWithMessage("no prepared connector: the split connector factory asks and connects as the menu does")
            .that(split)
            .contains("splitPane.splitWidget(focused, SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, orientation, null)");
        assertThat(methodBody(view, "public boolean canBroadcast() {")).contains("getTerminalPaneCount() > 1");
        assertThat(methodBody(view, "public void toggleBroadcast() {"))
            .contains("if (splitPane != null && (splitPane.isBroadcastMode() || canBroadcast())) {\n"
                + "            splitPane.toggleBroadcastMode();");
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
