package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How MainWindow feeds the tabs' most-recently-used order and hands it to the command palette,
 * pinned against the source because the window cannot be built without a stage: the selection
 * listener touches a tab and the removal listener forgets it, both except while tabs are removed
 * and re-added in bulk; regrouping, closing several tabs and dropping a tab run as such a bulk
 * change; the palette lists this window's tabs in that order and the other windows' terminal tabs;
 * and choosing another window's tab raises that window the way the Control API does.
 */
class TabPaletteWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path CONTROL_BRIDGE = Path.of("src/main/java/de/kortty/ui/ControlApiUiBridge.java");

    @Test
    void theSelectionTouchesAndTheRemovalForgetsUnlessTabsAreReorganized() throws IOException {
        String setup = methodBody(source(MAIN_WINDOW), "private void setupUI() {");

        assertThat(setup).contains("if (newTab != null && !reorganizingTabs) {\n"
            + "                tabMru.touch(newTab);\n            }");
        assertThat(setup).contains("if (!reorganizingTabs) {\n"
            + "                            tabMru.remove(removedTab);\n                        }");
    }

    @Test
    void bulkTabChangesRunAsAReorganization() throws IOException {
        String source = source(MAIN_WINDOW);

        assertThat(methodBody(source, "private void organizeTabsByGroup() {"))
            .contains("reorganizeTabs(this::sortTabsByGroup);");
        assertThat(methodBody(source, "private boolean closeTabsByUser(List<Tab> tabs, Tab keepSelected, CloseCause cause) {"))
            .contains("reorganizeTabs(() -> tabPane.getTabs().removeAll(targets));");
        String setup = methodBody(source, "private void setupUI() {");
        assertWithMessage("both drop handlers move the tab inside a reorganization")
            .that(count(setup, "reorganizeTabs(() -> {\n                sourcePane.getTabs().remove(tab);")).isEqualTo(2);
        assertThat(count(setup, "sourcePane.getTabs().remove(tab);")).isEqualTo(2);

        String reorganize = methodBody(source, "private void reorganizeTabs(Runnable change) {");
        assertThat(reorganize).contains("reorganizingTabs = true;");
        assertThat(reorganize).contains("} finally {");
        assertThat(reorganize).contains("tabMru.retainOnly(tabPane.getTabs());");
        assertWithMessage("the shown tab counts as used once the change is over")
            .that(reorganize.indexOf("tabMru.touch(selected);"))
            .isGreaterThan(reorganize.indexOf("tabMru.retainOnly(tabPane.getTabs());"));
    }

    @Test
    void thePaletteListsTheTabsInTheirOrderAndRaisesAnotherWindow() throws IOException {
        String source = source(MAIN_WINDOW);

        assertThat(methodBody(source, "private void showCommandPalette() {"))
            .contains("new TabPaletteSource(this::paletteOwnTabs, this::paletteOtherWindowTabs,");
        assertThat(methodBody(source, "private TabPaletteSource.WindowTabs paletteOwnTabs() {"))
            .contains("tabMru.order(tabPane.getTabs())");
        String others = methodBody(source, "private List<TabPaletteSource.WindowTabs> paletteOtherWindowTabs() {");
        assertThat(others).contains("window.tabMru.order(window.tabPane.getTabs())");
        assertThat(others).contains("if (tab instanceof TerminalTab) {");
        String select = methodBody(source, "private void selectTabFromPalette(Tab tab) {");
        assertThat(select).contains("WindowRaiser.raise(window.stage);");
        assertThat(select).contains("window.tabPane.getSelectionModel().select(tab);");

        assertThat(methodBody(source(CONTROL_BRIDGE), "private static void raise(MainWindow window) {"))
            .contains("WindowRaiser.raise(window.getStage());");
    }

    @Test
    void aTerminalRowIsTitledLikeTheTabBarAndNamesItsConnection() throws IOException {
        String rows = methodBody(source(Path.of("src/main/java/de/kortty/ui/TabPaletteRows.java")),
            "static TabPaletteSource.TabRow row(Tab tab, Runnable select) {");

        assertThat(rows).contains("terminalTab.getEffectiveTitle()");
        assertThat(rows).contains("TabPaletteSource.connectionDetail(");
        assertWithMessage("never the raw title a program in the terminal set")
            .that(rows).doesNotContain("getWindowTitle");
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
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
