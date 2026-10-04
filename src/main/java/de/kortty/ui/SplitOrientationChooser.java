package de.kortty.ui;

import javafx.geometry.Orientation;
import org.jetbrains.annotations.NotNull;

/**
 * Where <i>View → Panes → Split Pane</i> and Cmd/Ctrl+Shift+O put the new pane: to the right of a
 * wide pane, below a tall one, so that repeated splits of the focused pane keep the panes roughly
 * square instead of slicing them ever thinner in one direction.
 *
 * <p>A terminal cell is about twice as tall as it is wide, so a pane with at least twice as many
 * columns as rows is at least as wide as it is tall on screen. It is split to the right only when it
 * also has {@link #MIN_COLUMNS_TO_SPLIT_RIGHT} columns, so each half keeps a usable line width;
 * otherwise, and while the pane's size is not known yet, the new pane goes below. Pure, so it is
 * unit-tested without the JavaFX toolkit ({@link Orientation} is a plain enum).
 */
final class SplitOrientationChooser {

    /** The fewest columns a pane needs to be split to the right: each half keeps about 20. */
    static final int MIN_COLUMNS_TO_SPLIT_RIGHT = 40;

    /** How many columns per row make a pane count as wide: a cell is about twice as tall as wide. */
    static final int COLUMNS_PER_ROW_WHEN_SQUARE = 2;

    /** Which side of the split pane the new pane goes to. */
    enum SplitSide {
        /** Beside the pane, on its right: the panes share the width. */
        RIGHT(Orientation.HORIZONTAL),
        /** Below the pane: the panes share the height. */
        DOWN(Orientation.VERTICAL);

        private final Orientation orientation;

        SplitSide(Orientation orientation) {
            this.orientation = orientation;
        }

        /** The orientation of the split, as TerminalSplitPane takes it. */
        @NotNull Orientation orientation() {
            return orientation;
        }
    }

    private SplitOrientationChooser() {
    }

    /**
     * The side for a pane of {@code columns} by {@code rows} cells.
     *
     * @return {@link SplitSide#RIGHT} for a wide pane with enough columns, otherwise
     *     {@link SplitSide#DOWN}, also for an unknown (zero or negative) size
     */
    static @NotNull SplitSide choose(int columns, int rows) {
        if (columns < MIN_COLUMNS_TO_SPLIT_RIGHT || rows <= 0) {
            return SplitSide.DOWN;
        }
        return (long) columns >= (long) COLUMNS_PER_ROW_WHEN_SQUARE * rows ? SplitSide.RIGHT : SplitSide.DOWN;
    }
}
