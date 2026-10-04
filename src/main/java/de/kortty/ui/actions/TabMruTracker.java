package de.kortty.ui.actions;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The order in which the tabs of one window were last used, the most recent first. A window
 * {@link #touch touches} a tab when the user selects it and {@link #remove removes} it when the tab
 * leaves the window. The command palette lists the tabs in this order. FX-free and not thread-safe;
 * a window uses it on the FX thread only.
 *
 * <p>Tabs are compared with {@code equals}, which for a JavaFX tab is its identity. A window that
 * removes and re-adds its tabs in bulk (regrouping, closing several at once, a tab dropped in place)
 * ignores the selection changes meanwhile, then calls {@link #retainOnly} and touches the tab it
 * ends up showing.
 *
 * <p>Ctrl+Tab in most-recently-used order steps through the tabs with {@link #advance}: the first
 * step takes a snapshot of the order, so the tabs the cycle passes over do not reorder it, and
 * {@link #commit} ends the cycle and counts only the tab it stopped at as used.
 *
 * @param <T> the tab type
 */
public final class TabMruTracker<T> {

    /** How many tabs the order remembers; older ones are listed as if never used. */
    public static final int CAPACITY = 100;

    private final MruList<T> used = new MruList<>(CAPACITY);
    // The order the running Ctrl+Tab cycle steps through, taken at its first step; null between cycles.
    private List<T> cycle;
    private int cyclePosition;

    /** {@code tab} was just selected: it becomes the most recently used tab. */
    public void touch(T tab) {
        used.add(Objects.requireNonNull(tab, "tab"));
    }

    /** {@code tab} left the window, closed or moved to another one. */
    public void remove(T tab) {
        if (tab != null) {
            used.remove(tab);
        }
    }

    /** Forgets every tab that is not in {@code open}. */
    public void retainOnly(Collection<? extends T> open) {
        Set<T> openTabs = new HashSet<>(open);
        for (T tab : used.items()) {
            if (!openTabs.contains(tab)) {
                used.remove(tab);
            }
        }
    }

    /**
     * The tabs of {@code open}, the most recently used first, then the ones never used (or used too
     * long ago) in their order in {@code open}. A remembered tab that is not open is left out, and
     * every tab appears once.
     */
    public List<T> order(List<? extends T> open) {
        Set<T> openTabs = new LinkedHashSet<>(open);
        List<T> ordered = new ArrayList<>(openTabs.size());
        for (T tab : used.items()) {
            if (openTabs.remove(tab)) {
                ordered.add(tab);
            }
        }
        ordered.addAll(openTabs);
        return ordered;
    }

    /** The remembered tabs, the most recent first; open or not. */
    public List<T> recent() {
        return used.items();
    }

    /**
     * One Ctrl+Tab step through the tabs in most-recently-used order, and the tab to select, or
     * {@code null} when no tab is open. The first step of a cycle takes the {@link #order} of
     * {@code open} with {@code current} (the selected tab) first, so the first step forward reaches
     * the tab used before it and the first step backwards the one used longest ago; later steps go on
     * from there and wrap around. The order is not changed until {@link #commit}. A tab of the
     * snapshot that has closed meanwhile is skipped, and a tab opened during the cycle is not
     * reached; when every tab of the snapshot has closed, a new cycle starts over {@code open}.
     */
    public @Nullable T advance(List<? extends T> open, @Nullable T current, boolean backwards) {
        if (open.isEmpty()) {
            cycle = null;
            return null;
        }
        if (cycle == null) {
            List<T> snapshot = new ArrayList<>(order(open));
            if (current != null && snapshot.remove(current)) {
                snapshot.add(0, current);
            }
            cycle = snapshot;
            cyclePosition = 0;
        }
        Set<T> openTabs = new HashSet<>(open);
        int size = cycle.size();
        for (int step = 1; step <= size; step++) {
            int index = Math.floorMod(cyclePosition + (backwards ? -step : step), size);
            T tab = cycle.get(index);
            if (openTabs.contains(tab)) {
                cyclePosition = index;
                return tab;
            }
        }
        cycle = null;
        return advance(open, current, backwards);
    }

    /**
     * Ends the running Ctrl+Tab cycle, if any, and counts {@code selected}, the tab it stopped at, as
     * the most recently used tab; with no cycle running it only {@link #touch touches} the tab.
     */
    public void commit(@Nullable T selected) {
        cycle = null;
        if (selected != null) {
            touch(selected);
        }
    }

    /** Whether a Ctrl+Tab cycle has started and not yet been {@link #commit committed}. */
    public boolean isCycling() {
        return cycle != null;
    }
}
