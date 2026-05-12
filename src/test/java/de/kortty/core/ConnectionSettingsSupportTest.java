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
        globalSettings.setDefaultTerminalSettings(globalDefaults);

        ServerConnection connection = new ServerConnection();
        ConnectionSettings connectionSettings = new ConnectionSettings();
        connectionSettings.setUseGlobalSettings(true);
        connectionSettings.setCursorStyle("BLINK_BLOCK");
        connectionSettings.setBackgroundColor("#445566");
        connection.setSettings(connectionSettings);

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                connection,
                globalSettings);

        assertThat(effective.getCursorStyle()).isEqualTo("STEADY_BLOCK");
        assertThat(effective.getBackgroundColor()).isEqualTo("#112233");
        assertThat(connection.getSettings().getCursorStyle()).isEqualTo("BLINK_BLOCK");
    }

    @Test
    void effectiveTerminalSettingsKeepsConnectionSpecificSettingsWhenGlobalSettingsDisabled() {
        ConnectionSettings globalDefaults = new ConnectionSettings();
        globalDefaults.setCursorStyle("STEADY_BLOCK");

        ConnectionSettings connectionSettings = new ConnectionSettings();
        connectionSettings.setUseGlobalSettings(false);
        connectionSettings.setCursorStyle("BLINK_UNDERLINE");

        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                connectionSettings,
                globalDefaults);

        assertThat(effective.getCursorStyle()).isEqualTo("BLINK_UNDERLINE");
    }

    @Test
    void effectiveTerminalSettingsFallsBackToDefaultsWhenNoSettingsExist() {
        ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                (ConnectionSettings) null,
                null);

        assertThat(effective.getCursorStyle()).isEqualTo("BLINK_BLOCK");
    }
}
