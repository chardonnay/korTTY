package de.kortty.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Which closes go into the {@link ClosedTabHistory}: only the ones the user asked for, each as one
 * entry, so the history holds what someone may want back rather than every tab that went away.
 *
 * <ul>
 *   <li>A tab's close button, Close Tab (Cmd/Ctrl+W) and the Dashboard's Close: one entry for the
 *       tab.</li>
 *   <li>Close Other Tabs, Close Tabs to the Right and Close All Tabs: one entry for all the terminal
 *       tabs the command closed, so a single bulk close can neither push the rest of the history out
 *       nor take several steps to undo.</li>
 *   <li>A window the user closes while korTTY keeps running: one entry for its terminal tabs, which
 *       reopen together.</li>
 * </ul>
 *
 * <p>Nothing is remembered when closing the window ends korTTY (Quit, or the last window on Windows
 * and Linux): those tabs are the session that File › Restore Previous Session brings back, not a
 * close. {@link MainWindow} never calls this class for a tab
 * whose session ended on its own (exit, Ctrl+D, a remote logout), for the tabs opening a project
 * replaces, for regrouping or for a tab dragged to another window.
 */
final class RecentlyClosedRecorder {

    private final ClosedTabHistory history;

    RecentlyClosedRecorder(ClosedTabHistory history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    /**
     * The terminal tabs one user command closed, in tab order: one entry for all of them. Nothing when
     * the command closed no terminal tab.
     */
    void tabsClosed(List<ClosedTabHistory.ClosedTab> tabs) {
        if (!tabs.isEmpty()) {
            history.push(new ClosedTabHistory.Entry(tabs, false));
        }
    }

    /**
     * The terminal tabs of a window the user closed, in tab order: one entry, unless closing the window
     * ends korTTY.
     *
     * @param endsApplication whether korTTY quits with this window (Quit, or its last window on
     *                        Windows and Linux)
     */
    void windowClosed(List<ClosedTabHistory.ClosedTab> tabs, boolean endsApplication) {
        if (!endsApplication && !tabs.isEmpty()) {
            history.push(new ClosedTabHistory.Entry(tabs, true));
        }
    }

    /**
     * The window a reopen acts in: {@code asking} while it is open. A window that is closed already
     * can still ask on macOS, where the menu bar of a closed window stays the application's menu bar
     * once the last window closed, so its File menu still offers Reopen Closed Tab and Recently
     * Closed. Then the focused or last open window takes over, or a new window when none is open;
     * the closed window itself is never used, since tabs opened there would never be seen.
     *
     * @param isOpen     whether a window is still open
     * @param openWindow the focused or last open window, or {@code null} when none is open
     * @param newWindow  opens a new window and returns it, or {@code null} when that failed; only
     *                   called when no window is open
     * @return the window to reopen in, or {@code null} when there is none
     */
    static <W> W reopenWindow(W asking, Predicate<W> isOpen, Supplier<W> openWindow, Supplier<W> newWindow) {
        if (asking != null && isOpen.test(asking)) {
            return asking;
        }
        W open = openWindow.get();
        return open != null ? open : newWindow.get();
    }
}
