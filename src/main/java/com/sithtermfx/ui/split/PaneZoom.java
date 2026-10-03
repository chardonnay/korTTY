package com.sithtermfx.ui.split;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The zoom of one split pane: while a pane is zoomed it alone fills the split pane, and the panes
 * around it are out of the scene with the split controls that hold them, at the size they had.
 * Zooming swaps the pane for a placeholder at its place in its split control's items and makes the
 * pane the split pane's only child; {@link #restore()} puts both back and the divider positions every
 * split control had before. No split control is rebuilt, so the tree of panes, their numbers and
 * their order stay as they were.
 *
 * <p>The dividers have to be written back: replacing the first item of a JavaFX {@code SplitPane}
 * resets its divider to the middle, and the split controls lay out again when they return to the
 * scene. {@link #reapplyDividers()} writes them once more for the split pane to call after those
 * layout passes.
 *
 * <p>Generic over the node type {@code N} and the split control type {@code S}, with plain lists for
 * the children, so the zoom is unit-tested without the JavaFX toolkit; TerminalSplitPane passes its
 * nodes, a {@code SplitPane}'s items and its own children.
 *
 * @param <N> the node type
 * @param <S> the split control type, compared by reference
 */
final class PaneZoom<N, S> {

    /** Reads and writes the divider positions of one split control. */
    interface Dividers<S> {

        /** The control's divider positions, from 0 to 1, in order. */
        double @NotNull [] positions(@NotNull S split);

        /** Sets the control's divider positions. */
        void setPositions(@NotNull S split, double @NotNull [] positions);
    }

    /** The positions one split control had when the pane was zoomed. */
    private record SavedDividers<S>(@NotNull S split, double @NotNull [] positions) {
    }

    private final N pane;
    private final N placeholder;
    private final List<N> items;
    private final int index;
    private final List<N> host;
    private final List<N> hostChildren;
    private final List<SavedDividers<S>> dividers;
    private final Dividers<S> access;

    private PaneZoom(N pane, N placeholder, List<N> items, int index, List<N> host, List<N> hostChildren,
                     List<SavedDividers<S>> dividers, Dividers<S> access) {
        this.pane = pane;
        this.placeholder = placeholder;
        this.items = items;
        this.index = index;
        this.host = host;
        this.hostChildren = hostChildren;
        this.dividers = dividers;
        this.access = access;
    }

    /**
     * Zooms {@code pane}: saves the divider positions of {@code splits}, puts {@code placeholder} at the
     * pane's place in {@code items} and makes the pane the only child of {@code host}.
     *
     * @param pane        the node of the pane to zoom
     * @param items       the items of the split control that holds the pane
     * @param host        the children of the split pane, which hold the root of the tree
     * @param placeholder an empty node that keeps the pane's place
     * @param splits      every split control of the tree
     * @return the zoom, or {@code null} when {@code items} does not hold the pane (a pane that is
     *     the root of the tree, a tab's only pane, has nothing to zoom out of); nothing changed then
     */
    static <N, S> @Nullable PaneZoom<N, S> zoom(@NotNull N pane, @NotNull List<N> items, @NotNull List<N> host,
                                                @NotNull N placeholder, @NotNull List<? extends S> splits,
                                                @NotNull Dividers<S> access) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(placeholder, "placeholder");
        Objects.requireNonNull(access, "access");
        int index = indexOf(items, pane);
        if (index < 0) {
            return null;
        }
        List<SavedDividers<S>> saved = new ArrayList<>(splits.size());
        for (S split : splits) {
            saved.add(new SavedDividers<>(split, access.positions(split).clone()));
        }
        List<N> hostChildren = List.copyOf(host);
        items.set(index, placeholder);
        // The tree leaves the host first: a node has one parent, and the pane may still be in it.
        host.clear();
        host.add(pane);
        return new PaneZoom<>(pane, placeholder, items, index, host, hostChildren, saved, access);
    }

    /** The node of the zoomed pane. */
    @NotNull N pane() {
        return pane;
    }

    /**
     * Shows every pane again: the host gets back what it held, the pane its place in its split
     * control, and every split control the divider positions it had when the pane was zoomed.
     */
    void restore() {
        // Anything added to the host meanwhile stays, above what it held before.
        host.removeIf(node -> node == pane);
        host.addAll(0, hostChildren);
        int at = index < items.size() && items.get(index) == placeholder ? index : indexOf(items, placeholder);
        if (at >= 0) {
            items.set(at, pane);
        }
        reapplyDividers();
    }

    /**
     * The divider positions {@code split} had when the pane was zoomed, which the control itself may
     * have reset since, or {@code null} for a control that was not part of the tree then.
     */
    double @Nullable [] savedPositions(@NotNull S split) {
        for (SavedDividers<S> saved : dividers) {
            if (saved.split() == split) {
                return saved.positions().clone();
            }
        }
        return null;
    }

    /** Writes the saved divider positions to every split control again. */
    void reapplyDividers() {
        for (SavedDividers<S> saved : dividers) {
            access.setPositions(saved.split(), saved.positions().clone());
        }
    }

    /** The position of {@code node} in {@code nodes}, compared by reference, or -1. */
    private static <N> int indexOf(@NotNull List<N> nodes, @NotNull N node) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) {
                return i;
            }
        }
        return -1;
    }
}
