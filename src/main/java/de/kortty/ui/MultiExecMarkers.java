package de.kortty.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.StringJoiner;

/**
 * The texts of the multi-exec and broadcast markers outside the panes: the tab marker's tooltip, which
 * screen readers read for it too, and the status-bar label of every window. The pane badges are
 * {@code TerminalSplitPane}'s. Pure, so the texts are unit-tested without the JavaFX toolkit.
 */
final class MultiExecMarkers {

    /** Tab marker, multi-exec: "Multi-exec: {0} of {1} panes of this tab take part". */
    static final String TAB_MULTI_EXEC_KEY = "tab.tooltip.multiExec";
    /** Tab marker, broadcast mode: "Broadcast mode: what you type goes to all {0} panes of this tab". */
    static final String TAB_BROADCAST_KEY = "tab.tooltip.broadcast";
    /** Status bar: "Multi-exec · panes: {0} · tabs: {1} · windows: {2}". */
    static final String STATUS_ACTIVE_KEY = "statusBar.multiExec.active";
    /** Status bar, appended while members are held back: "left out right now: {0}". */
    static final String STATUS_SKIPPED_KEY = "statusBar.multiExec.skipped";
    /** Status bar: the link that stops multi-exec. */
    static final String STATUS_STOP_KEY = "statusBar.multiExec.stop";
    /** Status bar tooltip: what multi-exec does and why panes can be left out. */
    static final String STATUS_TOOLTIP_KEY = "statusBar.multiExec.tooltip";
    /** The context-menu toggle of a pane: "Multi-exec: Include This Pane". */
    static final String PANE_TOGGLE_KEY = "terminal.contextMenu.multiExec";
    /** The tab context-menu toggle: "Multi-exec: Include All Panes of This Tab". */
    static final String TAB_TOGGLE_KEY = "tab.contextMenu.multiExec";
    /** The dashboard's toggle on a pane row: "Multi-exec: Include This Pane". */
    static final String DASHBOARD_PANE_TOGGLE_KEY = "dashboard.pane.multiExec";

    /** Every key of the markers and toggles outside View → Multi-exec. */
    static final List<String> KEYS = List.of(TAB_MULTI_EXEC_KEY, TAB_BROADCAST_KEY, STATUS_ACTIVE_KEY,
        STATUS_SKIPPED_KEY, STATUS_STOP_KEY, STATUS_TOOLTIP_KEY, PANE_TOGGLE_KEY, TAB_TOGGLE_KEY,
        DASHBOARD_PANE_TOGGLE_KEY);

    /** Separates the parts of the status-bar text, as in the zoom badge. */
    static final String SEPARATOR = " · ";

    private MultiExecMarkers() {
    }

    /**
     * The tab marker's text: a line for the tab's panes that take part in multi-exec, and one for its
     * broadcast mode while that reaches another pane; {@code null} when what you type in the tab stays
     * in the pane you type in, and the tab shows no marker.
     *
     * @param members how many of the tab's panes take part in multi-exec
     * @param panes   the tab's panes
     * @param broadcast whether the tab's broadcast mode is on
     */
    static @Nullable String tabMarkerText(int members, int panes, boolean broadcast) {
        StringJoiner lines = new StringJoiner("\n");
        if (members > 0) {
            lines.add(I18n.get(TAB_MULTI_EXEC_KEY, members, Math.max(panes, members)));
        }
        if (broadcast && panes > 1) {
            lines.add(I18n.get(TAB_BROADCAST_KEY, panes));
        }
        return lines.length() > 0 ? lines.toString() : null;
    }

    /**
     * The status-bar text: how many panes take part and in how many tabs and windows, and, while some
     * of them get nothing for now, how many.
     *
     * @param held the members the guards hold back now
     */
    static @NotNull String statusText(@NotNull MultiExecMembership.Counts counts, int held) {
        String text = I18n.get(STATUS_ACTIVE_KEY, counts.panes(), counts.tabs(), counts.windows());
        return held > 0 ? text + SEPARATOR + I18n.get(STATUS_SKIPPED_KEY, held) : text;
    }
}
