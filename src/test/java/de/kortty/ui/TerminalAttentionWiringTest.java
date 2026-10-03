package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * The bell's way from the emulator to the tab: {@code KorttyTerminalPanel.beep()} tells the pane's
 * listener, {@link TerminalView} coalesces the bells onto the FX thread and hands them to the tab,
 * the tab asks {@link TerminalAttentionNotifier}, and the window clears the mark once the tab is
 * seen. The widget, the view, the tab and the window need a JavaFX stage, so their wiring is pinned
 * against the source; the decisions themselves are tested in {@code TerminalNotificationPolicyTest}
 * and {@code BellCoalescingTest}.
 */
class TerminalAttentionWiringTest {

    @Test
    void thePanelReportsEveryBellAndStaysSilent() throws IOException {
        String beep = body(source("KorttyTermWidget.java"), "public void beep() {");
        int listener = beep.indexOf("listener.run();");
        int vendor = beep.indexOf("super.beep();");
        assertWithMessage("the listener hears the bell").that(listener).isAtLeast(0);
        assertWithMessage("SithTermFX's own bell, silent through audibleBell() == false, still runs after it")
            .that(vendor).isGreaterThan(listener);
        assertWithMessage("a failing listener must not stop the emulator thread")
            .that(beep).contains("} catch (RuntimeException e) {");
        assertThat(source("TerminalView.java")).contains("public boolean audibleBell() {\n            return false;");
    }

    @Test
    void everyPaneCoalescesItsBellsOntoTheFxThread() throws IOException {
        String view = source("TerminalView.java");
        assertThat(body(view, "private void setupWidgetEventHandlers(SithTermFxWidget widget) {"))
            .contains("installBellListener(widget);");
        String install = body(view, "private void installBellListener(SithTermFxWidget widget) {");
        assertThat(install).contains("new BellCoalescer(Platform::runLater, count -> onPaneBell(widget));");
        assertThat(install).contains("korttyWidget.setBellListener(bells::ring);");
        String onBell = body(view, "private void onPaneBell(SithTermFxWidget widget) {");
        assertWithMessage("a pane that closed while its bell was on the way is ignored")
            .that(onBell).contains("if (listener == null || !getOrderedWidgets().contains(widget)) {");
        assertThat(body(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("korttyWidget.setBellListener(null);");
        assertWithMessage("a closed tab is neither marked nor announced")
            .that(body(view, "public void cleanup() {")).contains("bellListener = null;");
    }

    @Test
    void theTabAsksTheNotifierAndShowsTheMarkAfterTheAgentStatus() throws IOException {
        String tab = source("TerminalTab.java");
        assertThat(tab).contains(
            "this.terminalView.setBellListener(widget -> TerminalAttentionNotifier.shared().onBell(this, widget));");
        assertThat(tab).contains("List.of(agentStatusBadge, attentionBadge), tabGroup, getEffectiveTitle(), effectiveSuffix)");
        assertThat(tab).contains("connectionColorLine, attentionLine);");
        assertThat(TerminalTab.ATTENTION_BADGE).isEqualTo("🔔");
    }

    @Test
    void theNotifierDecidesOnTheBellAndNeverShowsTerminalText() throws IOException {
        String onBell = body(source("TerminalAttentionNotifier.java"), "public void onBell(TerminalTab tab, SithTermFxWidget widget) {");
        assertThat(onBell).contains("policy.decide(Kind.BELL, widget, state, toggles);");
        assertThat(onBell).contains("current != null && current.isTerminalBellNotificationsEnabled()");
        assertThat(onBell).contains("current == null || current.isCodingAgentNotificationsEnabled()");
        assertThat(onBell).contains("tab.markAttention(I18n.get(\"terminal.notify.bell.tooltip\"));");
        assertThat(onBell).contains("show(toastTitle(tab.getEffectiveTitle()), I18n.get(\"terminal.notify.bell.body\"));");
    }

    @Test
    void theWindowClearsTheMarkOnceTheTabIsSeen() throws IOException {
        String window = source("MainWindow.java");
        String clear = body(window, "private void clearAttentionOfSeenTab() {");
        assertThat(clear).contains("TerminalTab seen = isForegroundWindow() ? getActiveTerminalTab() : null;");
        assertThat(clear).contains("seen.clearAttention();");
        String foreground = body(window, "private void updateForegroundActivity() {");
        assertWithMessage("a window coming to the front shows its selected tab again")
            .that(foreground).contains("if (foreground) {\n            // The window came to the front: its selected tab is seen again.\n            clearAttentionOfSeenTab();");
        int selection = window.indexOf("tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {");
        int selectionEnd = window.indexOf("});", selection);
        assertWithMessage("selecting a tab shows it").that(window.substring(selection, selectionEnd))
            .contains("clearAttentionOfSeenTab();");
    }

    @Test
    void theSettingIsSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains(
            "globalSettings.setTerminalBellNotificationsEnabled(terminalBellNotificationsCheck.isSelected());");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"bell_notifications\", gs::isTerminalBellNotificationsEnabled, true));");
        assertThat(dialog).contains("terminalGrid.add(terminalBellNotificationsCheck, 0, terminalRow++, 2, 1);");
    }

    @Test
    void aNotificationIsTitledWithKorttyAndTheCleanedTabName() {
        assertThat(TerminalAttentionNotifier.toastTitle("prod-db")).isEqualTo("korTTY · prod-db");
        assertWithMessage("the server can set the tab's name, so its controls go")
            .that(TerminalAttentionNotifier.toastTitle("prod‮bd-gnitset\u001B[31m")).isEqualTo("korTTY · prodbd-gnitset[31m");
        String longName = "x".repeat(TerminalAttentionNotifier.MAX_TAB_NAME_CHARS + 20);
        assertThat(TerminalAttentionNotifier.toastTitle(longName))
            .isEqualTo("korTTY · " + "x".repeat(TerminalAttentionNotifier.MAX_TAB_NAME_CHARS));
        assertThat(TerminalAttentionNotifier.toastTitle(null)).isEqualTo("korTTY");
        assertThat(TerminalAttentionNotifier.toastTitle(" ‎ ")).isEqualTo("korTTY");
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty/ui", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration is {@code signature}, up to its closing brace at that indent. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing " + signature).that(start).isAtLeast(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}\n", start);
        assertWithMessage("no end of " + signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
