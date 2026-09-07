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
        Boolean cursorBlink = globalSettings != null ? globalSettings.isTerminalCursorBlink() : null;
        return effectiveTerminalSettings(connectionSettings, globalDefaults, cursorBlink);
    }

    public static ConnectionSettings effectiveTerminalSettings(
            ConnectionSettings connectionSettings,
            ConnectionSettings globalDefaults) {
        return effectiveTerminalSettings(connectionSettings, globalDefaults, null);
    }

    /**
     * Resolves the settings a terminal should use. {@code cursorBlink} is the user's global "Cursor
     * blinks" choice: the flag lives in the cursor style the terminal consumes, but that string also
     * belongs to color profiles and per-connection settings, all of which ship a blinking style. When
     * the choice is known it therefore wins over whatever the resolved style says — it is the only
     * place the user can set it, so nothing else may override it.
     */
    public static ConnectionSettings effectiveTerminalSettings(
            ConnectionSettings connectionSettings,
            ConnectionSettings globalDefaults,
            Boolean cursorBlink) {
        ConnectionSettings effective =
            connectionSettings == null || connectionSettings.isUseGlobalSettings()
                ? new ConnectionSettings(globalDefaults != null ? globalDefaults : new ConnectionSettings())
                : new ConnectionSettings(connectionSettings);
        if (cursorBlink != null) {
            effective.setCursorStyle(
                TerminalCursorBlink.withPreference(effective.getCursorStyle(), cursorBlink));
        }
        return effective;
    }
}
