package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where the Keyword highlighting section of Settings → Terminal is hooked in, which no headless test can
 * build: the three controls are stored on save, the highlighting service re-reads them right after the
 * settings file is written so open panes follow at once, the anonymous statistics see only the default
 * set's class, and a new pane that starts on the default set reports that source.
 */
class HighlightSettingsWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void savingStoresTheThreeHighlightingSettings() throws IOException {
        String apply = region(source("SettingsDialog.java"), "private boolean applySettings() {", "\n    }\n");

        assertThat(apply).contains("globalSettings.setTerminalHighlightingEnabled(terminalHighlightingEnabledCheck.isSelected());");
        assertThat(apply).contains(
            "globalSettings.setTerminalHighlightAlternateScreen(terminalHighlightAlternateScreenCheck.isSelected());");
        assertThat(apply).contains("globalSettings.setDefaultHighlightRuleSetId(\n"
            + "                HighlightSettingsSupport.storedValue(defaultHighlightSetCombo.getValue()));");
    }

    @Test
    void theServiceReloadsRightAfterTheSettingsAreWritten() throws IOException {
        String dialog = source("SettingsDialog.java");
        String save = region(dialog, "setResultConverter(dialogButton -> {", "notifyListeners();");

        int written = save.indexOf("app.getGlobalSettingsManager().save();");
        int reloaded = save.indexOf("reloadTerminalHighlighting();");
        assertThat(written).isAtLeast(0);
        assertWithMessage("open panes must follow the saved switch, option and default at once")
            .that(reloaded).isGreaterThan(written);

        String reload = region(dialog, "private void reloadTerminalHighlighting() {", "\n    }\n");
        assertThat(reload).contains("service.reload(globalSettings);");
        assertWithMessage("a failing reload must never break saving").that(reload).contains("catch (RuntimeException e)");
    }

    @Test
    void theOtherControlsFollowTheMasterSwitch() throws IOException {
        String dialog = source("SettingsDialog.java");

        assertThat(dialog).contains("terminalHighlightAlternateScreenCheck.disableProperty().bind("
            + "terminalHighlightingEnabledCheck.selectedProperty().not());");
        assertThat(dialog).contains(
            "defaultHighlightSetCombo.disableProperty().bind(terminalHighlightingEnabledCheck.selectedProperty().not());");
    }

    @Test
    void theStatisticsSeeTheDefaultSetsClassOnly() throws IOException {
        String tracked = region(source("SettingsDialog.java"), "private java.util.List<TrackedSetting> trackedSettings() {",
            "\n    }\n");

        assertThat(tracked).contains(
            "new TrackedSetting(\"terminal\", \"highlighting_enabled\", gs::isTerminalHighlightingEnabled, true)");
        assertThat(tracked).contains("new TrackedSetting(\"terminal\", \"highlighting_full_screen\",\n"
            + "                gs::isTerminalHighlightAlternateScreen, true)");
        assertThat(tracked).contains("new TrackedSetting(\"terminal\", \"highlighting_default_set\",\n"
            + "                () -> HighlightSettingsSupport.telemetryValue(gs.getDefaultHighlightRuleSetId()), true)");
        assertWithMessage("the raw id would name a user's set").that(tracked)
            .doesNotContain("gs::getDefaultHighlightRuleSetId");
    }

    @Test
    void aRestoredBackupReachesTheHighlightingService() throws IOException {
        String reload = region(source("MainWindow.java"), "private void reloadStoresAfterBackupImport() {", "\n    }\n");

        int settings = reload.indexOf("reloadAfterBackupImport(\"global settings\"");
        int highlighting = reload.indexOf("highlightService.reload(app.getGlobalSettingsManager().getSettings());");
        assertThat(settings).isAtLeast(0);
        assertWithMessage("the restored rule sets and default must replace the compiled ones")
            .that(highlighting).isGreaterThan(settings);
    }

    @Test
    void theMainWindowResyncsItsToggleAfterASave() throws IOException {
        String settings = region(source("MainWindow.java"), "private void showSettings() {", "dialog.showAndWait();");

        assertThat(settings).contains("syncHighlightingToggleItems();");
    }

    @Test
    void aNewPaneOnAnInheritedSetReportsTheDefaultOrConnectionSource() throws IOException {
        String view = source("TerminalView.java");
        String attach = region(view, "private void attachTerminalHighlighter(SithTermFxWidget widget) {", "\n    }\n");
        String report = region(view, "private void reportInheritedHighlightSet(", "\n    }\n");

        assertWithMessage("a pane is reported once it is set up, not while it is being created")
            .that(attach).contains("pendingHighlightReports.add(widget);");
        assertThat(attach).doesNotContain("reportInheritedHighlightSet(");
        assertThat(report).contains(
            "HighlightTelemetry.props(shown, HighlightTelemetry.inheritedSource(service.decidingLevel(selection)))");
        assertWithMessage("a pane that starts without highlighting is no activation").that(report)
            .contains("if (shown != null) {");
    }

    @Test
    void theFirstPaneIsReportedOnceTheSplitPaneIsBuiltAndAClosedPaneNever() throws IOException {
        String view = source("TerminalView.java");
        String init = region(view, "private void initializeTerminal() {", "splitPane.setOnLastWidgetSessionEnded(");

        assertWithMessage("the first pane is configured inside the split pane's constructor")
            .that(init.indexOf("reportPendingHighlightActivations();"))
            .isGreaterThan(init.indexOf("splitPane = new TerminalSplitPane("));
        String pending = region(view, "private void reportPendingHighlightActivation(SithTermFxWidget pane) {",
            "\n    }\n");
        assertWithMessage("reported at most once").that(pending).contains("!pendingHighlightReports.remove(pane)");
        assertWithMessage("a pane with a choice of its own (taken over from its parent) is no inherited activation")
            .that(pending).contains("paneHighlightOverride.containsKey(pane)");
        assertWithMessage("a split that never connected is released without a report")
            .that(region(view, "private void releaseTerminalHighlighter(SithTermFxWidget widget) {", "\n    }\n"))
            .contains("pendingHighlightReports.remove(widget);");
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
