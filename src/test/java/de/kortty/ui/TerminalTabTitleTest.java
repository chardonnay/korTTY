package de.kortty.ui;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A renamed terminal tab: the title is built from ordered slots (badges, group, name, suffix), the
 * custom name replaces only the connection's name, and that name is what the coding-agent panel,
 * the Control API and projects see. The pure parts are tested directly; the wiring is pinned
 * against the source, since neither {@link TerminalTab} nor {@link MainWindow} can be built without
 * a JavaFX stage.
 */
class TerminalTabTitleTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @DataProvider
    Object[][] slotCombinations() {
        // badge, group, name, suffix, expected
        return new Object[][] {
            {"", null, "prod-db", "", "prod-db"},
            {"⚡", null, "prod-db", "", "⚡ prod-db"},
            {"", "Ops", "prod-db", "", "[Ops] prod-db"},
            {"", null, "prod-db", " (DISCONNECT)", "prod-db (DISCONNECT)"},
            {"⚡", "Ops", "prod-db", "", "⚡ [Ops] prod-db"},
            {"⚡", null, "prod-db", " (DISCONNECT)", "⚡ prod-db (DISCONNECT)"},
            {"", "Ops", "prod-db", " (DISCONNECT)", "[Ops] prod-db (DISCONNECT)"},
            {"✋", "Ops", "prod-db", " (DISCONNECT)", "✋ [Ops] prod-db (DISCONNECT)"},
            {"", "   ", "prod-db", "", "prod-db"},
            {null, null, "prod-db", null, "prod-db"},
        };
    }

    @Test(dataProvider = "slotCombinations")
    void composeTitlePutsTheSlotsInOrderAndLeavesEmptyOnesOut(
            String badge, String group, String name, String suffix, String expected) {
        assertThat(TerminalTab.composeTitle(Arrays.asList(badge), group, name, suffix)).isEqualTo(expected);
    }

    @Test
    void laterBadgesFollowTheAgentStatusInSlotOrder() {
        assertThat(TerminalTab.composeTitle(List.of("⚡", "●"), "Ops", "web", "")).isEqualTo("⚡ ● [Ops] web");
        assertThat(TerminalTab.composeTitle(List.of("", "●"), null, "web", "")).isEqualTo("● web");
        assertThat(TerminalTab.composeTitle(null, null, "web", "")).isEqualTo("web");
        assertThat(TerminalTab.composeTitle(List.of(), null, null, " (DISCONNECT)")).isEqualTo(" (DISCONNECT)");
    }

    @Test
    void theCustomTitleReplacesOnlyTheConnectionName() {
        String name = TerminalTab.effectiveTitle("Billing primary", "db-07", "root", "10.0.0.7");
        assertThat(TerminalTab.composeTitle(List.of("⚡"), "Ops", name, " (DISCONNECT)"))
            .isEqualTo("⚡ [Ops] Billing primary (DISCONNECT)");
    }

    @Test
    void effectiveTitleFallsBackFromCustomTitleToDisplayNameToUserAtHost() {
        assertThat(TerminalTab.effectiveTitle("Billing", "db-07", "root", "db7")).isEqualTo("Billing");
        assertThat(TerminalTab.effectiveTitle(null, "db-07", "root", "db7")).isEqualTo("db-07");
        assertThat(TerminalTab.effectiveTitle("  ", "db-07", "root", "db7")).isEqualTo("db-07");
        assertThat(TerminalTab.effectiveTitle(null, null, "root", "db7")).isEqualTo("root@db7");
        assertThat(TerminalTab.effectiveTitle(null, " ", "root", "db7")).isEqualTo("root@db7");
        assertThat(TerminalTab.effectiveTitle(null, null, null, "db7")).isEqualTo("db7");
        assertThat(TerminalTab.effectiveTitle(null, null, "root", null)).isEqualTo("root");
        assertThat(TerminalTab.effectiveTitle(null, null, null, null)).isEmpty();
    }

    @Test
    void aCustomTitleIsCleanedTrimmedAndCapped() {
        assertThat(TerminalTab.normalizeCustomTitle("  Billing primary  ")).isEqualTo("Billing primary");
        assertWithMessage("an override must not make the name read differently from its characters")
            .that(TerminalTab.normalizeCustomTitle("prod‮bd-gnitset")).isEqualTo("prodbd-gnitset");
        assertWithMessage("an escape sequence must not reach the tab bar")
            .that(TerminalTab.normalizeCustomTitle("\u001B[31mred")).isEqualTo("[31mred");
        assertThat(TerminalTab.normalizeCustomTitle("two\nlines")).isEqualTo("two lines");

        String longName = "x".repeat(TerminalTab.MAX_CUSTOM_TITLE_LENGTH + 30);
        assertThat(TerminalTab.normalizeCustomTitle(longName)).hasLength(TerminalTab.MAX_CUSTOM_TITLE_LENGTH);
        assertThat(TerminalTab.MAX_CUSTOM_TITLE_LENGTH).isEqualTo(120);
    }

    @Test
    void aBlankCustomTitleMeansTheConnectionName() {
        assertThat(TerminalTab.normalizeCustomTitle(null)).isNull();
        assertThat(TerminalTab.normalizeCustomTitle("")).isNull();
        assertThat(TerminalTab.normalizeCustomTitle("   ")).isNull();
        assertThat(TerminalTab.normalizeCustomTitle("‮\u0007\t")).isNull();
    }

    @Test
    void confirmingTheConnectionNameKeepsFollowingTheConnection() {
        assertThat(TerminalTab.customTitleFromInput("db-07", "db-07")).isNull();
        assertThat(TerminalTab.customTitleFromInput("  db-07 ", "db-07")).isNull();
        assertThat(TerminalTab.customTitleFromInput("", "db-07")).isNull();
        assertThat(TerminalTab.customTitleFromInput("Billing", "db-07")).isEqualTo("Billing");
        assertThat(TerminalTab.customTitleFromInput("DB-07", "db-07")).isEqualTo("DB-07");
    }

    @Test
    void theTabBarRendersTheTitleFromTheSlotsAndKeepsTheSuffixOnRename() throws IOException {
        String tab = source("TerminalTab.java");
        String update = methodBody(tab, "private void updateTabTitle(String suffix) {");
        assertThat(update).contains("composeTitle(");
        assertThat(update).contains("getEffectiveTitle()");
        assertThat(update).contains("tabGroup");

        String setter = methodBody(tab, "public void setCustomTitle(String title) {");
        assertThat(setter).contains("normalizeCustomTitle(title)");
        assertWithMessage("a rename must not drop the (DISCONNECT) suffix")
            .that(setter).contains("updateTabTitle(lastTitleSuffix)");
    }

    @Test
    void theCodingAgentPanelAndTheControlApiReportTheRenamedTitle() throws IOException {
        String bridge = methodBody(source("CodingAgentUiBridge.java"), "static String tabTitleOf(TerminalTab tab) {");
        assertThat(bridge).contains("tab.getEffectiveTitle()");
        assertThat(bridge).doesNotContain("getDisplayName()");
        assertThat(source("ControlApiUiBridge.java")).contains("CodingAgentUiBridge.tabTitleOf(tab)");
    }

    @Test
    void renameIsOfferedInTheTabContextMenuAndTheFileMenu() throws IOException {
        String window = source("MainWindow.java");

        String contextMenu = methodBody(window, "private void setupTabContextMenu(TerminalTab terminalTab) {");
        int rename = contextMenu.indexOf("I18n.get(\"tab.contextMenu.rename\")");
        assertThat(rename).isAtLeast(0);
        assertWithMessage("Rename Tab is the first item of the tab menu")
            .that(rename).isLessThan(contextMenu.indexOf("I18n.get(\"tab.contextMenu.duplicate\")"));
        assertThat(contextMenu).contains("promptRenameTab(terminalTab)");

        String fileMenu = methodBody(window, "private Menu createFileMenu() {");
        assertThat(fileMenu).contains("I18n.get(\"menu.file.renameTab\")");
        assertThat(fileMenu).contains("promptRenameTab(terminalTab)");
        assertWithMessage("both macOS menu bars compute the state when the menu opens")
            .that(fileMenu).contains("fileMenu.setOnShowing(");
        assertWithMessage("no shortcut: the free keys belong to the program in the terminal")
            .that(fileMenu.substring(fileMenu.indexOf("MenuItem renameTab"), fileMenu.indexOf("MenuItem closeTab")))
            .doesNotContain("setAccelerator(");

        String prompt = methodBody(window, "private void promptRenameTab(TerminalTab terminalTab) {");
        assertThat(prompt).contains("TerminalTab.customTitleFromInput(");
        assertThat(prompt).contains("terminalTab.setCustomTitle(");
        assertThat(prompt).contains("dialog.renameTab.title");
    }

    @Test
    void projectsSaveTheCustomTitleInTheTabTitleAndRestoreIt() throws IOException {
        String window = source("MainWindow.java");

        String save = methodBody(window, "private Project createProjectFromCurrentState() {");
        String terminal = save.substring(save.indexOf("if (tab instanceof TerminalTab terminalTab) {"),
            save.indexOf("} else if (tab instanceof SFTPManagerTab"));
        assertWithMessage("only a name the user gave is saved, so an unrenamed tab keeps following its connection")
            .that(terminal).contains("sessionState.setTabTitle(terminalTab.getCustomTitle());");

        String load = methodBody(window, "private void loadProject(Project project) {");
        String restore = load.substring(load.indexOf("case TERMINAL -> {"), load.indexOf("case SFTP_MANAGER -> {"));
        assertThat(restore).contains("restoredTab.setCustomTitle(sessionState.getTabTitle());");
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        throw new AssertionError("unbalanced braces in " + signature);
    }
}
