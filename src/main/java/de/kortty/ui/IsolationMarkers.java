package de.kortty.ui;

import de.kortty.isolation.IsolationReport;
import de.kortty.isolation.IsolationState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * What a terminal tab shows about its sessions' isolation: the shield (none, outline, filled, with a
 * warning sign) and the spy of an incognito tab, with the texts their tooltips and screen readers give.
 * Pure, so the texts are unit-tested without the JavaFX toolkit.
 *
 * <p>The shield shows the weakest pane of the tab, so it is never stronger than the least isolated
 * session behind it, except that a pane whose sandbox could not be set up always shows the warning sign.
 * With panes of different states the tooltip names how many panes have which.
 */
final class IsolationMarkers {

    /** Shield tooltip: "Own process: this session cannot reach korTTY or other tabs". */
    static final String PROCESS_KEY = "tab.tooltip.isolation.process";
    /** Shield tooltip: "Sandbox ({0}): no access to korTTY's data, SSH keys or other tabs". */
    static final String SANDBOXED_KEY = "tab.tooltip.isolation.sandboxed";
    /** Shield tooltip: "Sandbox requested but not active: {0}". */
    static final String DEGRADED_KEY = "tab.tooltip.isolation.degraded";
    /** Tooltip line for a tab with panes of different isolation: "Panes: {0}". */
    static final String PANES_KEY = "tab.tooltip.isolation.panes";
    /** Part of {@link #PANES_KEY}: "{0} in a sandbox". */
    static final String COUNT_SANDBOXED_KEY = "tab.tooltip.isolation.count.sandboxed";
    /** Part of {@link #PANES_KEY}: "{0} in their own process". */
    static final String COUNT_PROCESS_KEY = "tab.tooltip.isolation.count.process";
    /** Part of {@link #PANES_KEY}: "{0} with the sandbox missing". */
    static final String COUNT_DEGRADED_KEY = "tab.tooltip.isolation.count.degraded";
    /** Part of {@link #PANES_KEY}: "{0} without isolation". */
    static final String COUNT_NONE_KEY = "tab.tooltip.isolation.count.none";
    /** Spy tooltip: "Incognito session: no log, journal, recording, Recently Closed entry or restore". */
    static final String INCOGNITO_KEY = "tab.tooltip.incognito";

    static final List<String> KEYS = List.of(PROCESS_KEY, SANDBOXED_KEY, DEGRADED_KEY, PANES_KEY,
        COUNT_SANDBOXED_KEY, COUNT_PROCESS_KEY, COUNT_DEGRADED_KEY, COUNT_NONE_KEY, INCOGNITO_KEY);

    /**
     * What the tab shows.
     *
     * @param shield        the shield's state; {@link IsolationState#NONE} shows no shield
     * @param shieldText    the shield's tooltip and accessible text, or null without a shield
     * @param tooltipLine   the line for the tab's own tooltip (the shield text, plus the panes when they
     *                      differ), or null when no pane is isolated
     * @param incognitoText the spy's tooltip and accessible text, or null for a normal tab
     */
    record Markers(@NotNull IsolationState shield, @Nullable String shieldText, @Nullable String tooltipLine,
                   @Nullable String incognitoText) {

        static final Markers NONE = new Markers(IsolationState.NONE, null, null, null);
    }

    private IsolationMarkers() {
    }

    /**
     * The markers for a tab whose panes report {@code panes}.
     *
     * @param panes     the isolation of every pane with a session; empty for a tab not connected yet
     * @param incognito whether the tab is incognito
     */
    static @NotNull Markers of(@NotNull List<IsolationReport> panes, boolean incognito) {
        String incognitoText = incognito ? I18n.get(INCOGNITO_KEY) : null;
        if (panes.isEmpty()) {
            return new Markers(IsolationState.NONE, null, null, incognitoText);
        }
        List<IsolationState> states = new ArrayList<>();
        Map<IsolationState, Integer> counts = new EnumMap<>(IsolationState.class);
        IsolationReport degraded = null;
        IsolationReport sandboxed = null;
        for (IsolationReport report : panes) {
            IsolationReport r = report != null ? report : IsolationReport.NONE;
            states.add(r.state());
            counts.merge(r.state(), 1, Integer::sum);
            if (r.state() == IsolationState.DEGRADED && degraded == null) {
                degraded = r;
            }
            if (r.state() == IsolationState.SANDBOXED && sandboxed == null) {
                sandboxed = r;
            }
        }
        IsolationState shield = IsolationState.aggregate(states);
        String shieldText = switch (shield) {
            case NONE -> null;
            case PROCESS -> I18n.get(PROCESS_KEY);
            case SANDBOXED -> I18n.get(SANDBOXED_KEY, sandboxed != null && sandboxed.backendId() != null
                ? sandboxed.backendId() : "");
            case DEGRADED -> I18n.get(DEGRADED_KEY, degraded != null && degraded.detail() != null
                ? degraded.detail() : "");
        };
        String panesLine = counts.size() > 1 ? I18n.get(PANES_KEY, countsText(counts)) : null;
        StringJoiner tooltip = new StringJoiner("\n");
        if (shieldText != null) {
            tooltip.add(shieldText);
        }
        if (panesLine != null) {
            tooltip.add(panesLine);
        }
        String tooltipLine = tooltip.length() > 0 ? tooltip.toString() : null;
        String fullShieldText = shieldText != null && panesLine != null ? shieldText + "\n" + panesLine : shieldText;
        return new Markers(shield, fullShieldText, tooltipLine, incognitoText);
    }

    private static String countsText(Map<IsolationState, Integer> counts) {
        StringJoiner parts = new StringJoiner(", ");
        addCount(parts, counts, IsolationState.SANDBOXED, COUNT_SANDBOXED_KEY);
        addCount(parts, counts, IsolationState.PROCESS, COUNT_PROCESS_KEY);
        addCount(parts, counts, IsolationState.DEGRADED, COUNT_DEGRADED_KEY);
        addCount(parts, counts, IsolationState.NONE, COUNT_NONE_KEY);
        return parts.toString();
    }

    private static void addCount(StringJoiner parts, Map<IsolationState, Integer> counts, IsolationState state,
                                 String key) {
        Integer count = counts.get(state);
        if (count != null && count > 0) {
            parts.add(I18n.get(key, count));
        }
    }
}
