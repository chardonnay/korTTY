package de.kortty.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which split pane the keyboard focus moves to: the neighbour in a direction for
 * Cmd+Option / Ctrl+Alt with an arrow key and <i>View → Panes → Focus Pane Left/Right/Up/Down</i>,
 * and the next or previous pane for <i>Next Pane</i> and <i>Previous Pane</i>.
 *
 * <p>Pure functions of the panes' bounds, so every layout is unit-tested without the JavaFX
 * toolkit; {@code TerminalSplitPane} measures the panes in scene coordinates and hands them in.
 * Panes are compared by identity.
 *
 * <p>A neighbour lies strictly on the chosen side of the focused pane: for {@link PaneDirection#RIGHT}
 * its left edge is at or right of the focused pane's right edge (within {@link #EDGE_TOLERANCE}, as
 * the bounds are fractional). Among those, a pane that overlaps the focused one across the
 * direction (vertically for left and right, horizontally for up and down) wins over one that only
 * touches a corner, then the smaller gap, then the centre nearest to the focused pane's centre across
 * the direction; a remaining tie keeps the order of the given panes. At the edge there is no
 * neighbour: directional focus does not wrap around, next and previous do.
 */
public final class PaneNavigator {

    /** How far, in pixels, a pane may reach past the focused pane's edge and still count as beside it. */
    static final double EDGE_TOLERANCE = 1.0;

    /** A direction the focus can move in. */
    public enum PaneDirection {
        LEFT, RIGHT, UP, DOWN
    }

    /** A pane's bounds; {@code x} and {@code y} are its top-left corner. */
    public record Rect(double x, double y, double width, double height) {

        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }

        double centerX() {
            return x + width / 2.0;
        }

        double centerY() {
            return y + height / 2.0;
        }
    }

    private PaneNavigator() {
    }

    /**
     * The pane the focus moves to from {@code origin} in {@code direction}.
     *
     * @param panes    the panes of the tab, the origin included, in the order ties are broken by
     * @param boundsOf a pane's bounds, or {@code null} for a pane that is not laid out (it is skipped)
     * @return the neighbour, or empty at the edge, for a single pane or an origin without bounds
     */
    public static <K> @NotNull Optional<K> neighbor(@Nullable K origin, @NotNull List<K> panes,
                                                    @NotNull Function<? super K, @Nullable Rect> boundsOf,
                                                    @NotNull PaneDirection direction) {
        Rect from = origin != null ? boundsOf.apply(origin) : null;
        if (from == null) {
            return Optional.empty();
        }
        K best = null;
        Candidate bestScore = null;
        for (K pane : panes) {
            if (pane == null || pane == origin) {
                continue;
            }
            Rect to = boundsOf.apply(pane);
            if (to == null) {
                continue;
            }
            Candidate score = score(from, to, direction);
            if (score != null && (bestScore == null || score.isBetterThan(bestScore))) {
                best = pane;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The pane after (or before) {@code current} in {@code panes}, wrapping around at the end. A
     * {@code current} that is not among the panes moves to the first (or the last) one.
     *
     * @return the pane to focus, or empty when there are fewer than two panes
     */
    public static <K> @NotNull Optional<K> next(@NotNull List<K> panes, @Nullable K current, boolean forward) {
        if (panes.size() < 2) {
            return Optional.empty();
        }
        int index = -1;
        for (int i = 0; i < panes.size(); i++) {
            if (panes.get(i) == current) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return Optional.ofNullable(panes.get(forward ? 0 : panes.size() - 1));
        }
        int size = panes.size();
        int target = forward ? (index + 1) % size : (index - 1 + size) % size;
        return Optional.ofNullable(panes.get(target));
    }

    /** How good a candidate is, or {@code null} when it does not lie on the chosen side. */
    private static @Nullable Candidate score(Rect from, Rect to, PaneDirection direction) {
        double gap;
        double overlap;
        double centerDistance;
        switch (direction) {
            case RIGHT -> {
                gap = to.x() - from.maxX();
                overlap = overlap(from.y(), from.maxY(), to.y(), to.maxY());
                centerDistance = Math.abs(to.centerY() - from.centerY());
            }
            case LEFT -> {
                gap = from.x() - to.maxX();
                overlap = overlap(from.y(), from.maxY(), to.y(), to.maxY());
                centerDistance = Math.abs(to.centerY() - from.centerY());
            }
            case DOWN -> {
                gap = to.y() - from.maxY();
                overlap = overlap(from.x(), from.maxX(), to.x(), to.maxX());
                centerDistance = Math.abs(to.centerX() - from.centerX());
            }
            case UP -> {
                gap = from.y() - to.maxY();
                overlap = overlap(from.x(), from.maxX(), to.x(), to.maxX());
                centerDistance = Math.abs(to.centerX() - from.centerX());
            }
            default -> throw new IllegalArgumentException("Unknown direction: " + direction);
        }
        if (gap < -EDGE_TOLERANCE) {
            return null;
        }
        return new Candidate(overlap > EDGE_TOLERANCE, Math.max(0, gap), centerDistance);
    }

    /** The length two ranges share, 0 when they do not. */
    private static double overlap(double start, double end, double otherStart, double otherEnd) {
        return Math.max(0, Math.min(end, otherEnd) - Math.max(start, otherStart));
    }

    private record Candidate(boolean overlaps, double gap, double centerDistance) {

        boolean isBetterThan(Candidate other) {
            if (overlaps != other.overlaps) {
                return overlaps;
            }
            if (Math.abs(gap - other.gap) > EDGE_TOLERANCE) {
                return gap < other.gap;
            }
            return centerDistance < other.centerDistance - 1e-9;
        }
    }
}
