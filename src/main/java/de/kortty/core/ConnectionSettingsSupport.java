package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;

/**
 * Resolves terminal settings without mutating saved connection or global settings objects.
 */
public final class ConnectionSettingsSupport {

    private ConnectionSettingsSupport() {
    }

    public static ConnectionSettings effectiveTerminalSettings(
            ServerConnection connection,
            GlobalSettings globalSettings) {
        ConnectionSettings connectionSettings = connection != null ? connection.getSettings() : null;
        ConnectionSettings globalDefaults = globalSettings != null ? globalSettings.getDefaultTerminalSettings() : null;
        return effectiveTerminalSettings(connectionSettings, globalDefaults);
    }

    public static ConnectionSettings effectiveTerminalSettings(
            ConnectionSettings connectionSettings,
            ConnectionSettings globalDefaults) {
        if (connectionSettings == null || connectionSettings.isUseGlobalSettings()) {
            return new ConnectionSettings(globalDefaults != null ? globalDefaults : new ConnectionSettings());
        }
        return new ConnectionSettings(connectionSettings);
    }
}
