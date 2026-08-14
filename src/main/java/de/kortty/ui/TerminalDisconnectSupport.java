package de.kortty.ui;

/**
 * Pure decision logic for the terminal disconnect listener: what happens to the tab when the
 * connection ends. Extracted so the policy is unit-testable without JavaFX.
 */
final class TerminalDisconnectSupport {

    /** What the tab does after a disconnect event. */
    enum Reaction {
        /** The user just requested a reconnect; this event is expected — change nothing. */
        IGNORE_RECONNECT_IN_PROGRESS,
        /** Another split pane is still connected; the split's auto-close removes the dead pane. */
        PANE_CLOSED_ONLY,
        /** Clean end without a running journal: close the tab like a finished local shell. */
        CLOSE_TAB,
        /**
         * A journal is running: keep the tab open and let the user decide — reconnect and
         * continue the journal, or end it with its closing summary. A {@code reboot} ends the
         * connection cleanly, but rarely the work.
         */
        KEEP_OPEN_JOURNAL_DECISION,
        /** Error or transient mosh drop without a journal: keep the tab with the reconnect bar. */
        KEEP_OPEN_DISCONNECTED
    }

    private TerminalDisconnectSupport() {
    }

    static Reaction reactionFor(
            boolean wasError,
            boolean reconnectInProgress,
            boolean moshSession,
            boolean remoteLogout,
            boolean splitHasOtherPanes,
            boolean journalActive) {

        if (!wasError) {
            if (reconnectInProgress) {
                return Reaction.IGNORE_RECONNECT_IN_PROGRESS;
            }
            // A mosh session treats a clean end as transient unless the server logged us out;
            // SSH has no transient clean end, so anything but a remote logout falls through too.
            if (!moshSession || remoteLogout) {
                if (splitHasOtherPanes) {
                    return Reaction.PANE_CLOSED_ONLY;
                }
                return journalActive ? Reaction.KEEP_OPEN_JOURNAL_DECISION : Reaction.CLOSE_TAB;
            }
        }
        return journalActive ? Reaction.KEEP_OPEN_JOURNAL_DECISION : Reaction.KEEP_OPEN_DISCONNECTED;
    }
}
