package de.kortty.ui;

/**
 * Whether a capture of the open session may become the session snapshot. The rules keep a good
 * snapshot from being replaced by a worse one:
 *
 * <ul>
 *   <li><b>Sealed.</b> Once korTTY has written the snapshot it quits with, nothing more is written:
 *       the windows that close one after another while it quits would otherwise leave a snapshot
 *       with fewer and fewer windows, and finally none.</li>
 *   <li><b>Not the writer.</b> A second korTTY (without the store's lock), a snapshot file that could
 *       not be read, or a restored backup that waits for the restart: nothing is written.</li>
 *   <li><b>Restore running.</b> While a project or session is opening its tabs, a capture would hold
 *       only part of them; it is written once the restore is done.</li>
 *   <li><b>Empty capture.</b> A capture without a tab never replaces a snapshot with tabs; the
 *       snapshot keeps its windows and only takes the new Recently Closed list. That is what remains
 *       when the last window closes on macOS, where korTTY keeps running.</li>
 * </ul>
 *
 * <p>Pure: no file, no toolkit.
 */
final class SessionAutosaveRule {

    private SessionAutosaveRule() {
    }

    /** What to do with a capture. */
    enum Decision {
        /** Write the capture. */
        WRITE,
        /** Write the snapshot's own windows with the capture's Recently Closed list. */
        KEEP_SAVED_WINDOWS,
        /** Not now; the change stays pending and is written later. */
        LATER,
        /** Never; the change is dropped. */
        DROP
    }

    /**
     * What the decision looks at.
     *
     * @param sealed       whether korTTY wrote the snapshot it quits with
     * @param writable     whether this korTTY may write the snapshot at all
     * @param restoring    whether a project or session restore is opening tabs right now
     * @param capturedTabs the tabs the capture holds, over all windows
     * @param savedTabs    the tabs the snapshot on disk holds, over all windows
     */
    record State(boolean sealed, boolean writable, boolean restoring, int capturedTabs, int savedTabs) {
    }

    static Decision decide(State state) {
        if (state.sealed() || !state.writable()) {
            return Decision.DROP;
        }
        if (state.restoring()) {
            return Decision.LATER;
        }
        if (state.capturedTabs() <= 0 && state.savedTabs() > 0) {
            return Decision.KEEP_SAVED_WINDOWS;
        }
        return Decision.WRITE;
    }
}
