package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;

import java.util.Objects;
import java.util.function.Consumer;

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

    /**
     * Whether a connection's terminal draws with its own settings. Only an explicit
     * {@code useGlobalSettings=false} counts: every new connection carries a settings object (with the
     * flag on), so the mere presence of one says nothing.
     */
    public static boolean usesOwnTerminalSettings(ConnectionSettings connectionSettings) {
        return connectionSettings != null && !connectionSettings.isUseGlobalSettings();
    }

    /**
     * The values an editor shows for a connection: its own settings when it has them, otherwise the
     * global defaults that apply to it now, so switching to "own settings" starts from what the
     * terminal already looks like. Always a copy.
     */
    public static ConnectionSettings editorSeed(ConnectionSettings connectionSettings,
                                                ConnectionSettings globalDefaults) {
        if (usesOwnTerminalSettings(connectionSettings)) {
            return new ConnectionSettings(connectionSettings);
        }
        return new ConnectionSettings(globalDefaults != null ? globalDefaults : new ConnectionSettings());
    }

    /**
     * The settings object the connection editor stores.
     *
     * <p>{@code own == false}: the connection follows the global settings. The stored values are
     * kept (with the flag on), so values that are read regardless of the flag, such as the SSH
     * keep-alive, and the user's earlier own values survive; {@code editedValues} is not applied.</p>
     *
     * <p>{@code own == true}: starts from the connection's own settings, or, when it followed the
     * global settings until now, from a copy of the global defaults (the values the editor showed)
     * with the stored keep-alive carried over; applies {@code editedValues} and switches the flag
     * off, so the values take effect.</p>
     */
    public static ConnectionSettings settingsToSave(boolean own,
                                                    ConnectionSettings stored,
                                                    ConnectionSettings globalDefaults,
                                                    Consumer<ConnectionSettings> editedValues) {
        if (!own) {
            ConnectionSettings kept = stored != null ? new ConnectionSettings(stored) : new ConnectionSettings();
            kept.setUseGlobalSettings(true);
            return kept;
        }
        ConnectionSettings result = editorSeed(stored, globalDefaults);
        if (stored != null && !usesOwnTerminalSettings(stored)) {
            result.setSshKeepAliveEnabled(stored.isSshKeepAliveEnabled());
            result.setSshKeepAliveInterval(stored.getSshKeepAliveInterval());
        }
        if (editedValues != null) {
            editedValues.accept(result);
        }
        result.setUseGlobalSettings(false);
        return result;
    }

    /**
     * The terminal appearance Quick Connect offers, as shown or as picked. Comparing the two tells
     * whether the user changed it; only then does the connection get its own settings.
     */
    public record TerminalAppearance(String themeId, String fontFamily, int fontSize,
                                     String foregroundColor, String backgroundColor,
                                     boolean terminalColorsEnabled) {

        /** Compares colours case-insensitively, since pickers and stored values differ in case. */
        public boolean sameAs(TerminalAppearance other) {
            return other != null
                && Objects.equals(themeId, other.themeId)
                && Objects.equals(fontFamily, other.fontFamily)
                && fontSize == other.fontSize
                && equalsIgnoreCase(foregroundColor, other.foregroundColor)
                && equalsIgnoreCase(backgroundColor, other.backgroundColor)
                && terminalColorsEnabled == other.terminalColorsEnabled;
        }

        private static boolean equalsIgnoreCase(String a, String b) {
            return a == null ? b == null : a.equalsIgnoreCase(b);
        }
    }

    /**
     * Whether a connection started from Quick Connect draws with its own settings: when it already
     * had them, or when the user changed the appearance the dialog showed ({@code shown}) to
     * {@code picked}. Unchanged values of a connection that follows the global settings keep it
     * following them, so a later change of the global settings still reaches it.
     */
    public static boolean quickConnectUsesOwnSettings(ConnectionSettings connectionSettings,
                                                      TerminalAppearance shown,
                                                      TerminalAppearance picked) {
        if (usesOwnTerminalSettings(connectionSettings)) {
            return true;
        }
        return picked != null && (shown == null || !shown.sameAs(picked));
    }
}
