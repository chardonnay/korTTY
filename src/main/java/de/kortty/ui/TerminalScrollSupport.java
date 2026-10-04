package de.kortty.ui;

import java.util.OptionalDouble;

/**
 * Scrolls a SithTermFX terminal so a given line shows at the top. SithTermFX keeps the view as a
 * <em>scroll origin</em>: the screen row shown at the top, from {@code -history} (the first
 * scrollback line) to {@code 0} (the bottom). It derives the origin from its JavaFX scroll bar with a
 * private mapping (a JavaFX bar's value spans {@code [min, max]}, a Swing one's
 * {@code [min, max - visible]}), and rounds:
 *
 * <pre>origin = round(min + (value - min) / (max - min) * (max - visible - min))</pre>
 *
 * <p>{@link #scrollValueForOrigin} is its exact inverse, so setting the value it returns makes
 * SithTermFX show exactly the wanted line, which its own {@code moveScrollBar} does not promise for
 * long jumps. Pure arithmetic, no JavaFX.
 */
final class TerminalScrollSupport {

    private TerminalScrollSupport() {
    }

    /**
     * The scroll origin that shows {@code absoluteLine} at the top, or as close to the top as the
     * scrollback allows: a line on the last screen can only be shown with the view at the bottom.
     *
     * @param absoluteLine the line counted from the first scrollback line
     * @param history      the number of scrollback lines
     */
    static int originForLine(long absoluteLine, int history) {
        int lines = Math.max(0, history);
        long origin = absoluteLine - lines;
        return (int) Math.max(-lines, Math.min(0, origin));
    }

    /**
     * The scroll-bar value that makes SithTermFX show {@code origin}, or empty when the bar cannot
     * scroll at all (no scrollback: {@code max - visible <= min}).
     */
    static OptionalDouble scrollValueForOrigin(int origin, double min, double max, double visible) {
        double swingRange = max - visible - min;
        if (!(swingRange > 0) || !(max > min)) {
            return OptionalDouble.empty();
        }
        double clamped = Math.max(min, Math.min(max - visible, origin));
        double value = min + (clamped - min) * (max - min) / swingRange;
        return OptionalDouble.of(Math.max(min, Math.min(max, value)));
    }

    /**
     * The origin SithTermFX shows for a scroll-bar value: its own private mapping, for the tests and
     * for reading the view back.
     */
    static int originForScrollValue(double value, double min, double max, double visible) {
        if (!(max > min)) {
            return 0;
        }
        double normalized = (value - min) / (max - min);
        return (int) Math.round(min + normalized * (max - visible - min));
    }
}
