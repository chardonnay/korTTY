package de.kortty.ui;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.Theme;
import org.jetbrains.annotations.Nullable;

/**
 * The theme a tab runs after the user picked one from the terminal context menu (Theme submenu):
 * for that tab and its panes, for the session only. It lives on the tab and is applied to the tab's
 * own settings copy, never to the connection's {@link ConnectionSettings} — a tab's connection
 * shares that object with the stored connection ({@code ServerConnection.copyForAuth}), so writing
 * there reached connections.xml on the next save. The saved connection changes only through the
 * connection editor or Settings.
 *
 * <p>FX-free so the rules are unit-tested; {@code TerminalView} owns one per tab (FX thread).
 */
final class RuntimeThemeOverride {

    private @Nullable Theme theme;

    /** The theme picked from the context menu, or null while the tab follows its connection. */
    @Nullable Theme theme() {
        return theme;
    }

    /**
     * Remembers {@code picked} for this tab and applies it to {@code tabSettings}, the tab's own
     * settings copy. A null theme or null settings changes nothing.
     */
    void select(@Nullable Theme picked, @Nullable ConnectionSettings tabSettings, boolean includeFont) {
        if (picked == null || tabSettings == null) {
            return;
        }
        theme = picked;
        picked.applyTo(tabSettings, includeFont);
        tabSettings.setThemeId(picked.getId());
    }

    /**
     * The settings a refresh (a connection-manager save, a Settings change) applies to the tab: the
     * freshly resolved {@code resolved} with the picked theme on top, so an unrelated save does not
     * take the tab's theme away. Returns {@code resolved} itself while no theme was picked, else a
     * copy; {@code resolved} is never changed.
     */
    ConnectionSettings applyOver(ConnectionSettings resolved, boolean includeFont) {
        if (theme == null || resolved == null) {
            return resolved;
        }
        ConnectionSettings withTheme = new ConnectionSettings(resolved);
        theme.applyTo(withTheme, includeFont);
        withTheme.setThemeId(theme.getId());
        return withTheme;
    }
}
