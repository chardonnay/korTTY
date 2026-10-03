package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;

import java.util.function.Function;

/**
 * Decides which members of a connection group Quick Connect's <b>Open Group</b> opens, by the rule
 * a single open and Duplicate use: a local shell runs a local process with no authentication and
 * SSH key auth needs no password, so both open without one; a password or keyboard-interactive
 * login opens only with a stored password and is skipped otherwise, because a group open asks no
 * questions. UI-free, so the rule is testable without a stage.
 */
final class GroupOpenSupport {

    private GroupOpenSupport() {
    }

    /** Whether {@code connection} needs no password to open: a local shell or SSH key auth. */
    static boolean opensWithoutPassword(ServerConnection connection) {
        return connection.isLocalShell() || connection.getAuthMethod() == AuthMethod.PUBLIC_KEY;
    }

    /**
     * Whether and with which password a group open starts {@code connection}. The stored password
     * is looked up only for a member that needs one, so a local shell or key-auth member opens with
     * {@code null} as in Duplicate.
     *
     * @param storedPassword the member's stored password (credential store or vault), {@code null}
     *                       when none is stored
     */
    static Decision decide(ServerConnection connection, Function<ServerConnection, String> storedPassword) {
        if (opensWithoutPassword(connection)) {
            return new Decision(true, null);
        }
        String password = storedPassword.apply(connection);
        if (password == null || password.isEmpty()) {
            return Decision.SKIP;
        }
        return new Decision(true, password);
    }

    /** {@code open} with {@code password} (may be {@code null}), or skip the member. */
    record Decision(boolean open, String password) {

        /** A password login without a stored password: not opened. */
        static final Decision SKIP = new Decision(false, null);

        /** Never prints the password. */
        @Override
        public String toString() {
            return "Decision[open=" + open + ", password=" + (password == null ? "none" : "***") + "]";
        }
    }
}
