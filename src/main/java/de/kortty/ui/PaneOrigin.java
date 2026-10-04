package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Where a terminal pane's session comes from: the connection it runs, the password it signed in
 * with and the temporary SSH key it used, if any.
 *
 * <p>A tab's first pane runs the tab's connection. A pane opened with "Split (new connection)" runs
 * the connection picked for it, which can be another server; a "same server" split of that pane
 * must open on that server, not on the tab's. {@link PaneOrigins} records the origin of each such
 * pane, and every pane without one runs the tab's.
 *
 * @param connection the connection the pane runs
 * @param password the password the pane signed in with, or null (key, local shell, prompt)
 * @param temporaryKey the temporary SSH key the pane signed in with, or null
 */
record PaneOrigin(ServerConnection connection, @Nullable String password, @Nullable TemporarySSHKey temporaryKey) {

    PaneOrigin {
        Objects.requireNonNull(connection, "connection");
    }

    /**
     * The origin a pane runs: the one recorded for it, or the tab's when nothing was recorded (the
     * tab's first pane, and every pane split from it on the same server).
     */
    static PaneOrigin resolve(@Nullable PaneOrigin recorded, PaneOrigin tab) {
        return recorded != null ? recorded : Objects.requireNonNull(tab, "tab");
    }

    /** Names the connection only: the password and the key must never reach a log line. */
    @Override
    public String toString() {
        return "PaneOrigin[" + connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort()
            + ", password=" + (password != null ? "set" : "none")
            + ", temporaryKey=" + (temporaryKey != null ? "set" : "none") + "]";
    }
}
