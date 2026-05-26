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
}
