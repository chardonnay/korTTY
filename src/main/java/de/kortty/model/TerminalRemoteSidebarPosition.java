package de.kortty.model;

import java.util.Locale;

/**
 * Where a terminal tab shows its remote files sidebar (View › Remote Files Sidebar): hidden by
 * default, on the right when turned on (D10). The {@link #name()} is what
 * {@code global-settings.xml} stores.
 */
public enum TerminalRemoteSidebarPosition {
    /** Not shown; no SFTP channel is opened for it. */
    HIDDEN,
    /** Left of the terminal panes. */
    LEFT,
    /** Right of the terminal panes. */
    RIGHT;

    /** The position for a stored value; a missing or unknown one is {@link #HIDDEN}. */
    public static TerminalRemoteSidebarPosition parse(String value) {
        if (value == null || value.isBlank()) {
            return HIDDEN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HIDDEN;
        }
    }
}
