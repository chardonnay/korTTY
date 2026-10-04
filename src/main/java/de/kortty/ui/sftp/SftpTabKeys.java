package de.kortty.ui.sftp;

import de.kortty.model.ServerConnection;
import org.jetbrains.annotations.Nullable;

/**
 * Which SFTP manager tabs count as the same one (D9). A standalone tab, with its own login, exists
 * once per connection, as before. A tab that borrows a terminal pane's SSH session exists once per
 * pane, so every split pane can open SFTP in its own folder. The two kinds of key never collide.
 */
public final class SftpTabKeys {

    private static final String CONNECTION_PREFIX = "connection:";
    private static final String PANE_PREFIX = "pane:";

    private SftpTabKeys() {
    }

    /**
     * The key of a standalone tab for {@code connection}, or null when the connection has no id
     * (a tab for such a connection is never reused).
     */
    public static @Nullable String standalone(@Nullable ServerConnection connection) {
        String id = connection != null ? connection.getId() : null;
        return id == null || id.isBlank() ? null : CONNECTION_PREFIX + id;
    }

    /**
     * The key of a tab borrowing the session of one terminal pane.
     *
     * @param terminalTabId the terminal tab's stable id
     * @param paneId        the pane's id within that tab
     */
    public static String borrowed(String terminalTabId, String paneId) {
        return PANE_PREFIX + nonNull(terminalTabId) + "/" + nonNull(paneId);
    }

    /** Whether {@code key} names a borrowed (per-pane) tab. */
    public static boolean isBorrowed(@Nullable String key) {
        return key != null && key.startsWith(PANE_PREFIX);
    }

    private static String nonNull(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A borrowed SFTP tab needs a terminal tab and pane id");
        }
        return value;
    }
}
