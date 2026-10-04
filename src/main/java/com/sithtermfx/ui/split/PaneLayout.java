package com.sithtermfx.ui.split;

import javafx.geometry.Orientation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An immutable picture of a split pane's tree of panes. A leaf holds one pane; a branch holds two
 * children, side by side ({@link Orientation#HORIZONTAL}) or one above the other
 * ({@link Orientation#VERTICAL}), and where the divider between them sits, from 0 to 1.
 *
 * <p>{@link TerminalSplitPane#snapshotLayout()} takes one of the live tree, and
 * {@link TerminalSplitPane#applyDividerPositions(PaneLayout)} moves the live dividers to those of a
 * layout with the same panes in the same places. A restored project builds one from the saved
 * layout with the panes it managed to open.
 *
 * <p>Generic in the pane type, so layouts are built and compared without the JavaFX toolkit:
 * TerminalSplitPane uses its widgets, a restore plan the numbers of the saved panes.
 *
 * @param pane        the leaf's pane, {@code null} for a branch
 * @param orientation the branch's orientation, {@code null} for a leaf
 * @param divider     the branch's divider position, from 0 to 1; 0 for a leaf
 * @param first       the branch's left or top child, {@code null} for a leaf
 * @param second      the branch's right or bottom child, {@code null} for a leaf
 * @param <W> the pane type
 */
public record PaneLayout<W>(@Nullable W pane, @Nullable Orientation orientation, double divider,
                            @Nullable PaneLayout<W> first, @Nullable PaneLayout<W> second) {

    public PaneLayout {
        boolean leaf = pane != null;
        if (leaf && (orientation != null || first != null || second != null)) {
            throw new IllegalArgumentException("a leaf has a pane and nothing else");
        }
        if (!leaf && (orientation == null || first == null || second == null)) {
            throw new IllegalArgumentException("a branch has an orientation and two children");
        }
    }

    /** A leaf holding {@code pane}. */
    public static <W> @NotNull PaneLayout<W> leaf(@NotNull W pane) {
        return new PaneLayout<>(Objects.requireNonNull(pane, "pane"), null, 0, null, null);
    }

    /** A branch holding {@code first} and {@code second}, the divider at {@code divider}. */
    public static <W> @NotNull PaneLayout<W> split(@NotNull Orientation orientation, double divider,
                                                   @NotNull PaneLayout<W> first, @NotNull PaneLayout<W> second) {
        return new PaneLayout<>(null, orientation, divider, first, second);
    }

    /** Whether this is a leaf, a single pane. */
    public boolean isLeaf() {
        return pane != null;
    }

    /** The panes from left to right and top to bottom, the order of {@link TerminalSplitPane#getAllWidgets()}. */
    public @NotNull List<W> panes() {
        List<W> panes = new ArrayList<>();
        collectPanes(this, panes);
        return panes;
    }

    /** The number of panes. */
    public int paneCount() {
        return isLeaf() ? 1 : first.paneCount() + second.paneCount();
    }

    /** The same tree with every pane replaced by {@code mapper}'s answer for it, which must not be null. */
    public <V> @NotNull PaneLayout<V> map(@NotNull Function<? super W, ? extends V> mapper) {
        if (isLeaf()) {
            return leaf(Objects.requireNonNull(mapper.apply(pane), "mapped pane"));
        }
        return split(orientation, divider, first.map(mapper), second.map(mapper));
    }

    /**
     * Whether {@code other} has the same tree as this layout: the same panes ({@code equals}) in the
     * same leaves and the same orientation in every branch. The divider positions are not compared.
     */
    public boolean sameShapeAs(@Nullable PaneLayout<?> other) {
        if (other == null || isLeaf() != other.isLeaf()) {
            return false;
        }
        if (isLeaf()) {
            return pane.equals(other.pane);
        }
        return orientation == other.orientation && first.sameShapeAs(other.first) && second.sameShapeAs(other.second);
    }

    private static <W> void collectPanes(PaneLayout<W> node, List<W> panes) {
        if (node.isLeaf()) {
            panes.add(node.pane);
            return;
        }
        collectPanes(node.first, panes);
        collectPanes(node.second, panes);
    }
}
