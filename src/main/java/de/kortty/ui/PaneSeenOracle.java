package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which terminal tab the user is looking at: the selected tab of the window in front (focused, not
 * minimised). There is at most one such window, so at most one tab is seen at any time; a tab in a
 * window behind another one, or not selected, is unseen.
 *
 * <p>Terminal notifications ({@link TerminalAttentionNotifier}) treat a seen tab as seen whatever
 * pane has the focus. The coding agents ({@link CodingAgentUiBridge#isSeen}) start from the same tab
 * and also require their pane to have the focus.
 *
 * <p>Nothing is cached: tabs move between windows and windows come and go, so every call walks the
 * open windows afresh. Call it on the JavaFX thread.
 */
final class PaneSeenOracle {

    private PaneSeenOracle() {
    }

    /** The terminal tab the user is looking at, or {@code null} when none is. */
    static @Nullable TerminalTab seenTab(List<MainWindow> windows) {
        return seenTab(windows, MainWindow::isForegroundWindow, MainWindow::getActiveTerminalTab);
    }

    /** Whether the user is looking at {@code tab}. */
    static boolean isSeen(@Nullable TerminalTab tab) {
        return tab != null && seenTab(MainWindow.getOpenWindows()) == tab;
    }

    /**
     * Whether the user is looking at {@code tab}, a tab of any kind such as an AI swarm's: the selected
     * tab of the window in front.
     */
    static boolean isTabSeen(@Nullable javafx.scene.control.Tab tab) {
        return tab != null && seenTab(MainWindow.getOpenWindows(), MainWindow::isForegroundWindow,
            MainWindow::getActiveTab) == tab;
    }

    /**
     * The selected terminal tab of the first window in front, or {@code null} when no window is in
     * front or that window shows another kind of tab.
     *
     * @param windows     the open windows
     * @param foreground  whether a window is in front: showing, not minimised and focused
     * @param selectedTab a window's selected tab when it is a terminal tab, else {@code null}
     */
    @Nullable
    static <W, T> T seenTab(@Nullable List<W> windows, Predicate<W> foreground,
            Function<W, T> selectedTab) {
        if (windows == null) {
            return null;
        }
        for (W window : new ArrayList<>(windows)) {
            if (window != null && foreground.test(window)) {
                return selectedTab.apply(window);
            }
        }
        return null;
    }
}
