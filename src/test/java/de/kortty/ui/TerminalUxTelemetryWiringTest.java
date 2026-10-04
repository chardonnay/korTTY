package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the terminal-UX usage events are sent: once per palette row run, once per user change to
 * multi-exec (not when a pane closes), once per restore of the previous session, always through the
 * {@code TerminalUxTelemetry} builders and the opt-in {@code Telemetry} facade. The consent flow is
 * left as it is. Sources are pinned, line-ending agnostic.
 */
class TerminalUxTelemetryWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");
    private static final Path ANONYMOUS_DATA = Path.of("app-docs/site/docs/en/about/anonymous-data.md");

    @Test
    void thePaletteReportsTheKindAndScopeOfEveryRowRunNeverTheQuery() throws IOException {
        String popup = source(UI.resolve("CommandPalettePopup.java"));
        assertThat(methodBody(popup, "private void run(PaletteEntry entry) {")).contains("reportUse(entry);");
        assertThat(methodBody(popup, "private void runAlternate(PaletteEntry entry) {")).contains("reportUse(entry);");
        String report = methodBody(popup, "private void reportUse(PaletteEntry entry) {");
        assertThat(report).contains("TelemetryEvents.COMMAND_PALETTE_USED");
        assertThat(report).contains("TerminalUxTelemetry.commandPaletteUsed(entry.kind(), scoped)");
        assertWithMessage("a disabled row runs nothing and is not counted")
            .that(methodBody(popup, "private void showReason(PaletteEntry entry) {")).doesNotContain("reportUse");
        assertWithMessage("the event never carries the row's title or key")
            .that(report).doesNotContain("entry.title()");
        assertThat(report).doesNotContain("entry.key()");
    }

    @Test
    void multiExecReportsUserChangesButNotPanesThatClose() throws IOException {
        String coordinator = source(UI.resolve("MultiExecCoordinator.java"));
        assertThat(methodBody(coordinator, "public boolean togglePane(@Nullable SithTermFxWidget pane) {"))
            .contains("reportChange();");
        assertThat(methodBody(coordinator,
            "public boolean setPanes(@NotNull Collection<SithTermFxWidget> panes, boolean included) {"))
            .contains("reportChange();");
        assertThat(methodBody(coordinator, "public void stop() {")).contains("reportChange();");
        assertWithMessage("a refused join reports nothing")
            .that(methodBody(coordinator, "private boolean joinRefused() {")).doesNotContain("reportChange");
        assertWithMessage("a pane that closed leaves without an event")
            .that(methodBody(coordinator, "public void forget(@Nullable SithTermFxWidget pane) {"))
            .doesNotContain("reportChange");
        String report = methodBody(coordinator, "private void reportChange() {");
        assertThat(report).contains("TelemetryEvents.MULTI_EXEC_CHANGED");
        assertThat(report).contains("TerminalUxTelemetry.multiExecChanged(membership.size() > 0, reach)");
    }

    @Test
    void everyRestoreOfThePreviousSessionReportsModeTriggerAndCounts() throws IOException {
        String window = source(UI.resolve("MainWindow.java"));
        assertThat(window).contains("restorePreviousSession.setOnAction(e -> restorePreviousSession(RestoreTrigger.MENU));");
        assertThat(methodBody(window, "public void startSessionRestore(de.kortty.model.SessionRestoreMode mode) {"))
            .contains("restorePreviousSession(RestoreTrigger.AUTO);");
        assertThat(methodBody(window, "private void showSessionRestoreOffer(String text) {"))
            .contains("restorePreviousSession(RestoreTrigger.OFFER);");

        String restore = methodBody(window, "private void restorePreviousSession(RestoreTrigger trigger) {");
        int opens = restore.indexOf("restoreProject(project, target);");
        int report = restore.indexOf("reportSessionRestored(trigger, windows.size(), SessionSnapshotStore.restorableTabs(project));");
        assertWithMessage("only a restore that opened something is reported").that(report).isGreaterThan(opens);
        assertThat(methodBody(window, "private void reportSessionRestored(RestoreTrigger trigger, int windows, int tabs) {"))
            .contains("TerminalUxTelemetry.sessionRestored(mode, trigger, windows, tabs)");
    }

    @Test
    void theSettingsDialogCountsTheNewTerminalUxSettingsWithoutTheirContent() throws IOException {
        String dialog = methodBody(source(UI.resolve("SettingsDialog.java")),
            "private java.util.List<TrackedSetting> trackedSettings() {");
        assertThat(dialog).contains("\"quick_select_alphabet_customized\",\n"
            + "                () -> gs.getTerminalQuickSelectAlphabet() != null, true));");
        assertThat(dialog).contains("\"quick_select_patterns_customized\",\n"
            + "                () -> !gs.getTerminalQuickSelectPatterns().isEmpty(), true));");
        assertThat(dialog).contains("\"tab_title_from_shell\", gs::isTabTitleFromShellEnabled, true));");
        assertThat(dialog).contains("\"tab_switch_most_recent_first\", gs::isTabSwitchMostRecentFirst, true));");
        assertThat(dialog).contains("\"shortcuts_customized\",\n"
            + "                () -> !gs.getKeyBindingOverrides().isEmpty(), true));");
    }

    @Test
    void theGuideListsEveryNewEventAndItsProps() throws IOException {
        String guide = source(ANONYMOUS_DATA);
        for (String event : new String[] {"command_palette_used", "multi_exec_changed", "session_restored",
            "terminal_highlight_applied"}) {
            assertWithMessage(event).that(guide).contains("`" + event + "`");
        }
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start + signature.length() - 1);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
