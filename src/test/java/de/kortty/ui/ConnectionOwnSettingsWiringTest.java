package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where the choice between the global terminal settings and a connection's own settings is wired
 * in, which no headless test can build. Until this was fixed the connection editor stored the
 * edited values with {@code useGlobalSettings} still on, so they never took effect; the values
 * themselves are checked in {@code ConnectionSettingsSupportTest}.
 */
class ConnectionOwnSettingsWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theEditorOffersAnExplicitChoice() throws IOException {
        String tab = region(source("ConnectionEditDialog.java"), "private Tab createSettingsTab() {", "\n    }\n");

        assertThat(tab).contains("new RadioButton(I18n.get(\"connEdit.terminalSettings.useGlobal\"))");
        assertThat(tab).contains("new RadioButton(I18n.get(\"connEdit.terminalSettings.own\"))");
        assertWithMessage("selected from the flag, not from whether a settings object exists")
            .that(tab).contains("ConnectionSettingsSupport.usesOwnTerminalSettings(connection.getSettings())");
        assertThat(tab).contains("ConnectionSettingsSupport.editorSeed(");
        assertThat(tab).contains("settingsGrid.setDisable(!ownSettings);");
        assertThat(tab).doesNotContain("useCustomSettingsCheck");
    }

    @Test
    void switchingTheChoiceShowsTheValuesThatApply() throws IOException {
        String tab = region(source("ConnectionEditDialog.java"), "private Tab createSettingsTab() {", "\n    }\n");

        assertWithMessage("switching to global shows the global values, not the greyed-out own ones")
            .that(tab).contains("loadTerminalFields(ConnectionSettingsSupport.editorSeed(null, globalTerminalDefaults()));");
        assertWithMessage("the own values typed so far come back when switching to own again")
            .that(tab).contains("ownSettingsDraft = new ConnectionSettings(connSettings);");
        assertThat(tab).contains("loadTerminalFields(ownSettingsDraft);");
    }

    @Test
    void aSavedSessionComparesZoomWithTheFontTheConnectionOpensWith() throws IOException {
        String main = source("MainWindow.java");

        assertWithMessage("stale own values of a global connection must not pin the global size as an override")
            .that(main).contains("currentFontSize != sessionFontSizeBaseline(connection.getSettings())");
        assertThat(region(main, "private int sessionFontSizeBaseline(ConnectionSettings connSettings) {", "\n    }\n"))
            .contains("ConnectionSettingsSupport.effectiveTerminalSettings(connSettings, globalDefaults).getFontSize()");
    }

    @Test
    void savingStoresTheChoiceWithTheFlag() throws IOException {
        String dialog = source("ConnectionEditDialog.java");
        String converter = region(dialog, "setResultConverter(dialogButton -> {", "saveTerminalEffectSettings();");

        assertThat(converter).contains("boolean ownSettings = ownSettingsRadio != null && ownSettingsRadio.isSelected();");
        assertThat(converter).contains("connection.setSettings(ConnectionSettingsSupport.settingsToSave(");
        assertWithMessage("a null settings object NPEs in SshTtyConnector and drops the keep-alive")
            .that(converter).doesNotContain("connection.setSettings(null)");
        assertThat(converter).doesNotContain("customSettings = new ConnectionSettings();");
    }

    @Test
    void savedSettingsReachOpenAndReconnectedTabs() throws IOException {
        String refresh = region(source("MainWindow.java"),
            "private void refreshAllTerminalTabsConnectionSettings() {", "// Propagate the connection's group");

        assertThat(refresh).contains("conn.setSettings(stored.getSettings());");
        assertWithMessage("applied unconditionally, so switching back to global reaches the open tab")
            .that(refresh).contains("terminalTab.applyConnectionSettings(stored.getSettings());");
        assertThat(refresh).doesNotContain("if (stored.getSettings() != null) {\n                            terminalTab");
    }

    @Test
    void quickConnectOnlyGivesOwnSettingsForAChangedAppearance() throws IOException {
        String quick = source("QuickConnectDialog.java");
        String apply = region(quick, "private void applyTerminalSettings(ServerConnection connection) {", "\n    }\n");

        assertThat(apply).contains("ConnectionSettingsSupport.quickConnectUsesOwnSettings(connection.getSettings(), shownAppearance, picked)");
        assertThat(apply).contains("ConnectionSettingsSupport.settingsToSave(");
        assertThat(apply).contains("applyTerminalLogSettings(connection);");
        assertWithMessage("a picked connection shows the values it actually uses")
            .that(quick).contains("ConnectionSettingsSupport.editorSeed(conn.getSettings(), globalTerminalDefaults())");
        assertThat(quick).contains("shownAppearance = currentAppearance();");
    }

    @Test
    void zoomResetIgnoresStaleValuesOfAConnectionThatFollowsTheGlobalSettings() throws IOException {
        String view = source("TerminalView.java");

        assertThat(view).contains("ConnectionSettingsSupport.usesOwnTerminalSettings(connection.getSettings())\n"
            + "                        ? connection.getSettings() : null;");
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
