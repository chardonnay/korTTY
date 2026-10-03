package de.kortty.ui;

import javafx.scene.control.Tab;

import java.util.ArrayList;
import java.util.List;

/**
 * Which tabs <em>Close Other Tabs</em> and <em>Close Tabs to the Right</em> close, worked out from
 * the window's tab order alone so it needs no stage. Both name only tabs that may close (a tab
 * whose closing is switched off stays), never the anchor itself, and nothing at all when the anchor
 * is not among the tabs: a menu that is still open for a tab that has moved to another window or
 * has closed closes nothing. Tabs are compared by identity, in tab order.
 */
final class TabCloseTargets {

    private TabCloseTargets() {
    }

    /**
     * Every closable tab except {@code anchor}.
     *
     * @return the tabs in tab order; empty when {@code anchor} is {@code null} or not in {@code tabs}
     */
    static List<Tab> others(List<? extends Tab> tabs, Tab anchor) {
        int anchorIndex = indexOf(tabs, anchor);
        if (anchorIndex < 0) {
            return List.of();
        }
        List<Tab> targets = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            addIfClosable(targets, tabs.get(i), i != anchorIndex);
        }
        return List.copyOf(targets);
    }

    /**
     * The closable tabs after {@code anchor}.
     *
     * @return the tabs in tab order; empty when {@code anchor} is the last tab, {@code null} or not in
     *     {@code tabs}
     */
    static List<Tab> toTheRight(List<? extends Tab> tabs, Tab anchor) {
        int anchorIndex = indexOf(tabs, anchor);
        if (anchorIndex < 0) {
            return List.of();
        }
        List<Tab> targets = new ArrayList<>();
        for (int i = anchorIndex + 1; i < tabs.size(); i++) {
            addIfClosable(targets, tabs.get(i), true);
        }
        return List.copyOf(targets);
    }

    /**
     * Whether closing several tabs at once asks one question first: only when at least one of them
     * is a terminal whose own close button would ask ({@link TerminalTab#needsCloseConfirmation()}:
     * split panes or a command still running), and the global setting to close active terminals
     * without confirmation is off, as for <em>Close All Tabs</em>.
     */
    static boolean needsSummaryConfirmation(int terminalsThatWouldAsk, boolean closeActiveWithoutConfirmation) {
        return terminalsThatWouldAsk > 0 && !closeActiveWithoutConfirmation;
    }

    private static void addIfClosable(List<Tab> targets, Tab tab, boolean candidate) {
        if (candidate && tab != null && tab.isClosable()) {
            targets.add(tab);
        }
    }

    private static int indexOf(List<? extends Tab> tabs, Tab anchor) {
        if (tabs == null || anchor == null) {
            return -1;
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) == anchor) {
                return i;
            }
        }
        return -1;
    }
}
