package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

/**
 * Wording for the warning shown before an AI agent or planning run starts in a pane whose shell
 * was switched to another identity (after {@code su}, {@code sudo -i} or a nested {@code ssh}).
 *
 * <p>The agent never types into that shell: it runs its commands over a new exec channel of the
 * tab's original SSH session, or as local processes in a local-shell tab. The warning therefore
 * names the identity the agent really acts as — the one the tab was opened with — not the one the
 * prompt shows. Kept free of JavaFX so the wording and the "ask once per flow" rule are testable.</p>
 */
final class TerminalAgentTargetNotice {

    private TerminalAgentTargetNotice() {
    }

    /**
     * Describes the identity the agent acts as: {@code user@host} for SSH tabs (the session's
     * expected identity, falling back to the connection's user name and host), and "user on this
     * computer" for local-shell tabs, where the agent starts local processes. Falls back to a
     * generic phrase when nothing is known.
     */
    static String describeTarget(
        @Nullable String expectedUser,
        @Nullable String expectedHost,
        @Nullable String connectionUser,
        @Nullable String connectionHost,
        boolean localShell) {

        String user = firstNonBlank(expectedUser, connectionUser);
        if (localShell) {
            return user != null
                ? I18n.get("ai.agent.foreignSession.localTarget", user)
                : I18n.get("ai.agent.foreignSession.unknownTarget");
        }
        String identity = identity(user, firstNonBlank(expectedHost, connectionHost));
        return identity != null ? identity : I18n.get("ai.agent.foreignSession.unknownTarget");
    }

    /**
     * True when a run must ask before it starts: the pane's session looks foreign and the user has
     * not already acknowledged the warning for this very connector earlier in the same flow (a
     * planning run and the execution of its accepted plan, or a run and its Retry). A connector
     * that is unknown is never treated as acknowledged.
     */
    static boolean requiresConfirmation(
        boolean foreignSession,
        @Nullable Object targetConnector,
        @Nullable Object acknowledgedConnector) {

        if (!foreignSession) {
            return false;
        }
        return targetConnector == null || targetConnector != acknowledgedConnector;
    }

    /** {@code user@host}, or whichever half is known; {@code null} when neither is. */
    static @Nullable String identity(@Nullable String user, @Nullable String host) {
        String trimmedUser = trimToNull(user);
        String trimmedHost = trimToNull(host);
        if (trimmedUser != null && trimmedHost != null) {
            return trimmedUser + "@" + trimmedHost;
        }
        return trimmedUser != null ? trimmedUser : trimmedHost;
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        String trimmedFirst = trimToNull(first);
        return trimmedFirst != null ? trimmedFirst : trimToNull(second);
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
