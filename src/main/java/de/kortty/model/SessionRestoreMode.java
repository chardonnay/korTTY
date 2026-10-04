package de.kortty.model;

import java.util.Locale;

/**
 * What korTTY does at startup with the windows and tabs of the session before this start (the
 * previous session that File › Restore Previous Session opens). Settings › Window › Session Restore.
 *
 * <p>The {@link #id()} is what {@code global-settings.xml} stores; it must stay stable across
 * releases.
 */
public enum SessionRestoreMode {

    /** Show a bar in the window with Restore and Dismiss; nothing opens until the user chooses Restore. */
    ASK("ask"),

    /**
     * Reopen the previous session by itself, once no dialog is open. Asks like {@link #ASK} instead
     * after korTTY ended unexpectedly within a minute of the last restore.
     */
    AUTO("auto"),

    /** Do nothing at startup; File › Restore Previous Session still opens the previous session. */
    OFF("off");

    /** The mode a missing, empty or unknown stored value means. */
    public static final SessionRestoreMode DEFAULT = ASK;

    private final String id;

    SessionRestoreMode(String id) {
        this.id = id;
    }

    /** The stable stored form: {@code ask}, {@code auto} or {@code off}. */
    public String id() {
        return id;
    }

    /**
     * The mode with this id, compared without regard to case or surrounding blanks, or
     * {@link #DEFAULT} when it is missing or unknown (a value a newer korTTY wrote, or a hand-edited
     * file), so a damaged file never makes korTTY open connections by itself.
     */
    public static SessionRestoreMode fromId(String id) {
        if (id == null) {
            return DEFAULT;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (SessionRestoreMode mode : values()) {
            if (mode.id.equals(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }
}
