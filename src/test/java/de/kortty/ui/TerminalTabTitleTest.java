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
        String name = TerminalTab.effectiveTitle("Billing primary", null, "db-07", "root", "10.0.0.7");
        assertThat(TerminalTab.composeTitle(List.of("⚡"), "Ops", name, " (DISCONNECT)"))
            .isEqualTo("⚡ [Ops] Billing primary (DISCONNECT)");
    }

    @Test
    void effectiveTitleFallsBackFromCustomTitleToDisplayNameToUserAtHost() {
        assertThat(TerminalTab.effectiveTitle("Billing", null, "db-07", "root", "db7")).isEqualTo("Billing");
        assertThat(TerminalTab.effectiveTitle(null, null, "db-07", "root", "db7")).isEqualTo("db-07");
        assertThat(TerminalTab.effectiveTitle("  ", null, "db-07", "root", "db7")).isEqualTo("db-07");
        assertThat(TerminalTab.effectiveTitle(null, null, null, "root", "db7")).isEqualTo("root@db7");
        assertThat(TerminalTab.effectiveTitle(null, null, " ", "root", "db7")).isEqualTo("root@db7");
        assertThat(TerminalTab.effectiveTitle(null, null, null, null, "db7")).isEqualTo("db7");
        assertThat(TerminalTab.effectiveTitle(null, null, null, "root", null)).isEqualTo("root");
        assertThat(TerminalTab.effectiveTitle(null, null, null, null, null)).isEmpty();
    }

    @DataProvider
    Object[][] titleSlotPrecedence() {
        // custom title, shell title, display name, user, host, expected name
        return new Object[][] {
            {"Billing", "root@db7: /var/log", "db-07", "root", "db7", "Billing"},
            {null, "root@db7: /var/log", "db-07", "root", "db7", "root@db7: /var/log"},
            {"  ", "root@db7: /var/log", "db-07", "root", "db7", "root@db7: /var/log"},
            {null, null, "db-07", "root", "db7", "db-07"},
            {null, "   ", "db-07", "root", "db7", "db-07"},
            {null, "vim", null, "root", "db7", "vim"},
            {null, null, null, "root", "db7", "root@db7"},
        };
    }

    @Test(dataProvider = "titleSlotPrecedence")
    void theShellTitleRanksBelowTheCustomTitleAndAboveTheConnectionName(
            String custom, String shell, String displayName, String user, String host, String expected) {
        assertThat(TerminalTab.effectiveTitle(custom, shell, displayName, user, host)).isEqualTo(expected);
    }

    @Test
    void theShellTitleFillsOnlyTheNameSlot() {
        String name = TerminalTab.effectiveTitle(null, "root@db7: ~", "db-07", "root", "db7");
        assertWithMessage("the badges, the group and the status suffix stay around a title from the shell")
            .that(TerminalTab.composeTitle(List.of("⚡"), "Ops", name, " (DISCONNECT)"))
            .isEqualTo("⚡ [Ops] root@db7: ~ (DISCONNECT)");
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
    void confirmingTheShellsTitleKeepsFollowingTheShellAndTypingTheConnectionNamePinsIt() {
        // The rename dialog compares with what the tab shows on its own: the shell's title here.
        String automatic = "root@db7: ~";
        assertThat(TerminalTab.customTitleFromInput("root@db7: ~", automatic)).isNull();
        assertThat(TerminalTab.customTitleFromInput("", automatic)).isNull();
        assertWithMessage("the connection's name is a real choice while the shell names the tab")
            .that(TerminalTab.customTitleFromInput("db-07", automatic)).isEqualTo("db-07");
    }

    @Test
    void theTooltipNamesTheConnectionWhenTheShellNamesTheTab() {
        assertThat(TerminalTab.tooltipText("Connection: root@db7", null, null)).isNull();
        assertThat(TerminalTab.tooltipText("Connection: root@db7", " ", "")).isNull();
        assertThat(TerminalTab.tooltipText("Connection: root@db7", "Title set by the shell; the connection is named db-07", null))
            .isEqualTo("Connection: root@db7\nTitle set by the shell; the connection is named db-07");
        assertThat(TerminalTab.tooltipText("Connection: root@db7", null, "Tab color: red (#D32F2F)"))
            .isEqualTo("Connection: root@db7\nTab color: red (#D32F2F)");
        assertThat(TerminalTab.tooltipText("Connection: root@db7", "Title set by the shell", "Tab color: red"))
            .isEqualTo("Connection: root@db7\nTitle set by the shell\nTab color: red");
        assertThat(TerminalTab.tooltipText(null, "Title set by the shell", null)).isEqualTo("Title set by the shell");
    }

    @Test
    void theAttentionMarkFollowsTheAgentStatusAndExplainsItselfInTheTooltip() {
        assertThat(TerminalTab.composeTitle(List.of("⚡", TerminalTab.ATTENTION_BADGE), "Ops", "web", " (DISCONNECT)"))
            .isEqualTo("⚡ 🔔 [Ops] web (DISCONNECT)");
        assertThat(TerminalTab.composeTitle(List.of("", TerminalTab.ATTENTION_BADGE), null, "web", ""))
            .isEqualTo("🔔 web");

        String bell = "The bell rang while you were not looking at this tab.";
        assertWithMessage("the reason explains the mark in words and makes a tooltip on its own")
            .that(TerminalTab.tooltipText("Connection: root@db7", null, null, bell))
            .isEqualTo("Connection: root@db7\n" + bell);
        assertThat(TerminalTab.tooltipText("Connection: root@db7", "Title set by the shell", "Tab color: red", bell))
            .isEqualTo("Connection: root@db7\nTitle set by the shell\nTab color: red\n" + bell);
        assertThat(TerminalTab.tooltipText("Connection: root@db7", null, null, " ")).isNull();
        assertThat(TerminalTab.tooltipText("Connection: root@db7", null, null, null)).isNull();
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
    void everyPaneReportsItsTitleThroughTheTrackerOnTheFxThread() throws IOException {
        String view = source("TerminalView.java");

        assertWithMessage("the hand-over to the tab is marshalled onto the FX thread")
            .that(view).contains("new ShellTitleTracker<>(Platform::runLater, this::getFocusedWidget, "
                + "TerminalView::isTabTitleFromShellEnabled);");
        assertWithMessage("every pane, the first and every split, registers when its handlers are set up")
            .that(methodBody(view, "private void setupWidgetEventHandlers(SithTermFxWidget widget) {"))
            .contains("installShellTitleListener(widget);");
        String install = methodBody(view, "private void installShellTitleListener(SithTermFxWidget widget) {");
        assertThat(install).contains("terminal.addApplicationTitleListener(listener);");
        assertWithMessage("the emulator thread only hands the raw title to the tracker")
            .that(install).contains("title -> shellTitles.titleChanged(widget, title)");
        assertThat(install).doesNotContain("setText(");

        assertThat(methodBody(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("releaseShellTitleListener(widget);");
        assertThat(methodBody(view, "private void releaseShellTitleListener(SithTermFxWidget widget) {"))
            .contains("removeApplicationTitleListener(listener)");
        assertThat(methodBody(view, "public void cleanup() {")).contains("releaseAllShellTitleListeners();");
        assertWithMessage("the tab shows the title of the pane the user works in")
            .that(methodBody(view, "private void onPaneFocused(SithTermFxWidget widget) {"))
            .contains("shellTitles.publish();");

        String connect = methodBody(view, "public void connect() {");
        int reset = connect.indexOf("shellTitles.paneReset(terminalWidget);");
        assertWithMessage("a reconnect drops the old session's title before the new session starts")
            .that(reset).isAtLeast(0);
        assertThat(reset).isLessThan(connect.indexOf("terminalWidget.setTtyConnector("));
    }

    @Test
    void theShellTitleNeverChangesAColor() throws IOException {
        String tab = source("TerminalTab.java");

        assertThat(tab).contains("this.terminalView.setShellTitleListener(this::onShellTitleChanged);");
        String onTitle = methodBody(tab, "private void onShellTitleChanged(String title) {");
        assertThat(onTitle).contains("updateTabTitle(lastTitleSuffix)");
        for (String colorCall : List.of("setStyle", "setBorder", "setGraphic", "applyConnectionColor",
                "showConnectionColor", "setTabErrorColor", "resetTabColor")) {
            assertWithMessage("a title from the server must not touch " + colorCall)
                .that(onTitle).doesNotContain(colorCall);
        }
        assertWithMessage("the effective title ranks the custom title first")
            .that(methodBody(tab, "public String getEffectiveTitle() {"))
            .contains("effectiveTitle(customTitle, shellTitle,");
        assertWithMessage("the tooltip says which connection a tab named by its shell is")
            .that(methodBody(tab, "private void refreshTooltip() {"))
            .contains("I18n.get(\"tab.tooltip.shellTitle\", getConnectionTitle())");
    }

    @Test
    void theRenameDialogStartsFromWhatTheTabShowsOnItsOwn() throws IOException {
        String prompt = methodBody(source("MainWindow.java"), "private void promptRenameTab(TerminalTab terminalTab) {");

        assertThat(prompt).contains("String automaticTitle = terminalTab.getAutomaticTitle();");
        assertThat(prompt).contains("TerminalTab.customTitleFromInput(input, automaticTitle)");
        assertThat(prompt).contains("I18n.get(\"dialog.renameTab.headerShellTitle\", automaticTitle, terminalTab.getConnectionTitle())");
    }

    @Test
    void theWindowSettingSwitchesTheShellTitleInEveryWindow() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains("new CheckBox(I18n.get(\"settings.window.tabTitleFromShell\"))");
        assertThat(dialog).contains(
            "tabTitleFromShellCheck.setSelected(globalSettings == null || globalSettings.isTabTitleFromShellEnabled());");
        assertThat(dialog).contains(
            "globalSettings.setTabTitleFromShellEnabled(tabTitleFromShellCheck.isSelected());");
        int header = dialog.indexOf("I18n.get(\"settings.window.tabs.header\")");
        int check = dialog.indexOf("I18n.get(\"settings.window.tabTitleFromShell\")");
        int fixedGeometry = dialog.indexOf("I18n.get(\"settings.window.fixedGeometry.header\")");
        assertWithMessage("the switch sits in the Tabs section of the Window tab")
            .that(check).isGreaterThan(header);
        assertThat(check).isLessThan(fixedGeometry);

        String window = source("MainWindow.java");
        assertThat(methodBody(window, "private void showSettings() {")).contains("refreshShellTitlesInAllWindows();");
        assertThat(methodBody(window, "private static void refreshShellTitlesInAllWindows() {"))
            .contains("terminalTab.refreshShellTitle();");
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
        assertWithMessage("a title the server set is never written into a project")
            .that(terminal).doesNotContain("getShellTitle()");
        assertThat(terminal).doesNotContain("getEffectiveTitle()");

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
