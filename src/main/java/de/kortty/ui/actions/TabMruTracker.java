package de.kortty.ui.actions;

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
 * @param <T> the tab type
 */
public final class TabMruTracker<T> {

    /** How many tabs the order remembers; older ones are listed as if never used. */
    public static final int CAPACITY = 100;

    private final MruList<T> used = new MruList<>(CAPACITY);

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
}
