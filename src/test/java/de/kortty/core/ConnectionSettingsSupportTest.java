package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class ConnectionSettingsSupportTest {

    @Test
    void effectiveTerminalSettingsUsesGlobalDefaultsWhenConnectionUsesGlobalSettings() {
        GlobalSettings globalSettings = new GlobalSettings();
        ConnectionSettings globalDefaults = new ConnectionSettings();
        globalDefaults.setCursorStyle("STEADY_BLOCK");
        globalDefaults.setBackgroundColor("#112233");
        globalDefaults.setTerminalColorsEnabled(false);
        globalSettings.setDefaultTerminalSettings(globalDefaults);

        ServerConnection connection = new ServerConnection();
        ConnectionSettings connectionSettings = new ConnectionSettings();
        connectionSettings.setUseGlobalSettings(true);
        connectionSettings.setCursorStyle("BLINK_BLOCK");
        connectionSettings.setBackgroundColor("#445566");
        connectionSettings.setTerminalColorsEnabled(true);
        connection.setSettings(connectionSettings);

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                connection,
                globalSettings);

        assertThat(effective.getCursorStyle()).isEqualTo("STEADY_BLOCK");
        assertThat(effective.getBackgroundColor()).isEqualTo("#112233");
        assertThat(effective.isTerminalColorsEnabled()).isFalse();
        assertThat(connection.getSettings().getCursorStyle()).isEqualTo("BLINK_BLOCK");
        assertThat(connection.getSettings().isTerminalColorsEnabled()).isTrue();
    }

    @Test
    void effectiveTerminalSettingsKeepsConnectionSpecificSettingsWhenGlobalSettingsDisabled() {
        ConnectionSettings globalDefaults = new ConnectionSettings();
        globalDefaults.setCursorStyle("STEADY_BLOCK");
        globalDefaults.setTerminalColorsEnabled(false);

        ConnectionSettings connectionSettings = new ConnectionSettings();
        connectionSettings.setUseGlobalSettings(false);
        connectionSettings.setCursorStyle("BLINK_UNDERLINE");
        connectionSettings.setTerminalColorsEnabled(true);

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                connectionSettings,
                globalDefaults);

        assertThat(effective.getCursorStyle()).isEqualTo("BLINK_UNDERLINE");
        assertThat(effective.isTerminalColorsEnabled()).isTrue();
    }

    @Test
    void effectiveTerminalSettingsFallsBackToDefaultsWhenNoSettingsExist() {
        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                (ConnectionSettings) null,
                null);

        assertThat(effective.getCursorStyle()).isEqualTo("BLINK_BLOCK");
        assertThat(effective.isTerminalColorsEnabled()).isTrue();
    }

    @Test
    void effectiveTerminalSettingsCarryTheGlobalAnsiPaletteAndSelection() {
        ConnectionSettings globalDefaults = new ConnectionSettings();
        globalDefaults.setAnsiRed("#FF8800");
        globalDefaults.setSelectionColor("#FFFF00");
        globalDefaults.setAnsiPaletteCustomized(true);

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                new ConnectionSettings(), // uses the global settings
                globalDefaults,
                true);

        assertThat(effective.isAnsiPaletteCustomized()).isTrue();
        assertThat(effective.getAnsiRed()).isEqualTo("#FF8800");
        assertThat(effective.getSelectionColor()).isEqualTo("#FFFF00");
        assertThat(TerminalPaletteSupport.effectiveHex(effective, 1, false)).isEqualTo("#FF8800");
    }

    // ---- Own vs global terminal settings (connection editor, Quick Connect) ----

    private static ConnectionSettings globalDefaults() {
        ConnectionSettings global = new ConnectionSettings();
        global.setFontFamily("Menlo");
        global.setFontSize(16);
        global.setBackgroundColor("#101010");
        global.setAnsiRed("#AA0000");
        global.setAnsiPaletteCustomized(true);
        global.setScrollbackLines(50_000);
        return global;
    }

    @Test
    void onlyAnExplicitFlagMeansOwnSettings() {
        assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(null)).isFalse();
        // Every new ServerConnection carries a settings object with the flag on.
        assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(new ServerConnection().getSettings())).isFalse();
        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(own)).isTrue();
    }

    @Test
    void editorSeedShowsTheGlobalValuesForAConnectionThatFollowsThem() {
        ConnectionSettings stale = new ConnectionSettings();
        stale.setFontSize(9);

        ConnectionSettings seed = ConnectionSettingsSupport.editorSeed(stale, globalDefaults());

        assertThat(seed.getFontFamily()).isEqualTo("Menlo");
        assertThat(seed.getFontSize()).isEqualTo(16);
        assertThat(ConnectionSettingsSupport.editorSeed(null, null).getFontSize()).isEqualTo(14);
    }

    @Test
    void editorSeedShowsOwnValuesAsACopy() {
        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        own.setFontSize(20);

        ConnectionSettings seed = ConnectionSettingsSupport.editorSeed(own, globalDefaults());
        seed.setFontSize(30);

        assertThat(own.getFontSize()).isEqualTo(20);
        assertThat(ConnectionSettingsSupport.editorSeed(own, globalDefaults()).getFontSize()).isEqualTo(20);
    }

    @Test
    void savingOwnSettingsSwitchesTheFlagOffSoTheTerminalUsesThem() {
        ServerConnection connection = new ServerConnection();
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultTerminalSettings(globalDefaults());

        connection.setSettings(ConnectionSettingsSupport.settingsToSave(
            true, connection.getSettings(), globalSettings.getDefaultTerminalSettings(),
            s -> {
                s.setFontSize(22);
                s.setBackgroundColor("#330000");
            }));

        assertThat(connection.getSettings().isUseGlobalSettings()).isFalse();
        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(connection, globalSettings);
        assertThat(effective.getFontSize()).isEqualTo(22);
        assertThat(effective.getBackgroundColor()).isEqualTo("#330000");
        // Values the editor does not show start from the global ones the terminal used until now.
        assertThat(effective.getFontFamily()).isEqualTo("Menlo");
        assertThat(effective.getAnsiRed()).isEqualTo("#AA0000");
        assertThat(effective.isAnsiPaletteCustomized()).isTrue();
        assertThat(effective.getScrollbackLines()).isEqualTo(50_000);
    }

    @Test
    void switchingToOwnSettingsKeepsTheConnectionsKeepAlive() {
        ConnectionSettings stored = new ConnectionSettings();
        stored.setSshKeepAliveEnabled(false);
        stored.setSshKeepAliveInterval(120);

        ConnectionSettings saved = ConnectionSettingsSupport.settingsToSave(true, stored, globalDefaults(), null);

        assertThat(saved.isSshKeepAliveEnabled()).isFalse();
        assertThat(saved.getSshKeepAliveInterval()).isEqualTo(120);
    }

    @Test
    void editingOwnSettingsAgainStartsFromThem() {
        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        own.setAnsiRed("#00AA00");
        own.setFontSize(18);

        ConnectionSettings saved = ConnectionSettingsSupport.settingsToSave(
            true, own, globalDefaults(), s -> s.setFontSize(19));

        assertThat(saved.isUseGlobalSettings()).isFalse();
        assertThat(saved.getFontSize()).isEqualTo(19);
        assertThat(saved.getAnsiRed()).isEqualTo("#00AA00");
        assertThat(saved).isNotSameInstanceAs(own);
    }

    @Test
    void choosingGlobalSettingsKeepsTheOwnValuesButFollowsTheGlobalOnes() {
        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        own.setFontSize(18);
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultTerminalSettings(globalDefaults());
        ServerConnection connection = new ServerConnection();

        connection.setSettings(ConnectionSettingsSupport.settingsToSave(
            false, own, globalSettings.getDefaultTerminalSettings(),
            s -> { throw new AssertionError("edited values are not applied for global settings"); }));

        assertThat(connection.getSettings()).isNotNull();
        assertThat(connection.getSettings().isUseGlobalSettings()).isTrue();
        assertThat(connection.getSettings().getFontSize()).isEqualTo(18);
        assertThat(ConnectionSettingsSupport.effectiveTerminalSettings(connection, globalSettings).getFontSize())
            .isEqualTo(16);
        assertThat(ConnectionSettingsSupport.settingsToSave(false, null, null, null).isUseGlobalSettings()).isTrue();
    }

    @Test
    void theFlagSurvivesDuplicateExportAndImport() {
        ServerConnection source = new ServerConnection();
        source.setName("prod");
        source.setHost("prod.example");
        source.getSettings().setUseGlobalSettings(false);
        source.getSettings().setFontSize(21);

        for (ServerConnection copy : java.util.List.of(
                ServerConnection.copyForDuplicate(source),
                ServerConnection.copyForExport(source, true, false, true, true),
                ServerConnection.copyForImport(source, true, false, true, true))) {
            assertThat(copy.getSettings().isUseGlobalSettings()).isFalse();
            assertThat(copy.getSettings().getFontSize()).isEqualTo(21);
        }
    }

    @Test
    void quickConnectKeepsFollowingTheGlobalSettingsWhenNothingChanged() {
        ConnectionSettingsSupport.TerminalAppearance shown = new ConnectionSettingsSupport.TerminalAppearance(
            null, "Menlo", 16, "#FFFFFF", "#101010", true);
        ConnectionSettingsSupport.TerminalAppearance samePicked = new ConnectionSettingsSupport.TerminalAppearance(
            null, "Menlo", 16, "#ffffff", "#101010", true);

        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(new ConnectionSettings(), shown, samePicked))
            .isFalse();
        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(null, shown, samePicked)).isFalse();
    }

    @Test
    void quickConnectUsesOwnSettingsWhenTheAppearanceChangedOrTheConnectionHasThem() {
        ConnectionSettingsSupport.TerminalAppearance shown = new ConnectionSettingsSupport.TerminalAppearance(
            null, "Menlo", 16, "#FFFFFF", "#101010", true);
        ConnectionSettings global = new ConnectionSettings();

        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(global, shown,
            new ConnectionSettingsSupport.TerminalAppearance(null, "Menlo", 18, "#FFFFFF", "#101010", true))).isTrue();
        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(global, shown,
            new ConnectionSettingsSupport.TerminalAppearance("dracula", "Menlo", 16, "#FFFFFF", "#101010", true))).isTrue();
        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(global, shown,
            new ConnectionSettingsSupport.TerminalAppearance(null, "Menlo", 16, "#FFFFFF", "#101010", false))).isTrue();
        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(global, shown,
            new ConnectionSettingsSupport.TerminalAppearance(null, "Menlo", 16, "#FFFFFF", "#202020", true))).isTrue();

        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        assertThat(ConnectionSettingsSupport.quickConnectUsesOwnSettings(own, shown, shown)).isTrue();
    }
}
