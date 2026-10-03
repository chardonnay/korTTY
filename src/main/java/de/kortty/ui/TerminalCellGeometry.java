package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A snapshot of one terminal panel's cell grid and the maths that maps a mouse point onto a cell.
 * {@link KorttyTermWidget.KorttyTerminalPanel#cellGeometry()} takes it from the live panel.
 *
 * <p>{@link #cellAt(double, double)} mirrors SithTermFX's private
 * {@code TerminalPanel.panelPointToCell}, which its selection and link hit-testing use, so a korTTY
 * mouse hook and SithTermFX agree on the cell under the mouse. On top of that it applies the bounds
 * of korTTY's pinned SithTermFX patch {@code terminal-panel-bottom-row-hyperlink-boundary}: a cell
 * exists only for {@code 0 <= column < bufferWidth} and {@code -historyLines <= line < bufferHeight}.
 *
 * <p>{@link #underline} goes the other way, from cells to pixels, for the underline korTTY draws
 * under a hovered link.
 *
 * <p>Toolkit-free, because a SithTermFX {@code TerminalPanel} needs a running JavaFX toolkit.
 *
 * @param insetX       the left margin in pixels before the first column
 * @param cellWidth    the cell width in pixels
 * @param cellHeight   the cell height in pixels
 * @param columns      the columns the panel lays out
 * @param rows         the rows the panel lays out
 * @param scrollOrigin the buffer line in the top row; negative while scrolled back into the history
 * @param bufferWidth  the width of the text buffer
 * @param bufferHeight the screen lines of the text buffer
 * @param historyLines the lines in the text buffer's scrollback history
 */
public record TerminalCellGeometry(
        int insetX,
        double cellWidth,
        double cellHeight,
        int columns,
        int rows,
        int scrollOrigin,
        int bufferWidth,
        int bufferHeight,
        int historyLines) {

    /**
     * Pixels below the text baseline at which SithTermFX draws an underline, and so korTTY's hover
     * underline too.
     */
    public static final double UNDERLINE_BELOW_BASELINE = 3;

    /**
     * One row's part of an underline, in canvas coordinates.
     *
     * @param startX the left end, the left edge of the first cell
     * @param endX   the right end, the right edge of the last cell
     * @param y      the height of the line
     */
    public record Segment(double startX, double endX, double y) {
    }

    /**
     * Maps a point in canvas coordinates onto a cell. The left inset belongs to the first column,
     * and points right of the last column or below the last row land on that column or row, as in
     * SithTermFX.
     *
     * @return the column ({@code x}) and buffer line ({@code y}, negative in the history), or
     *         {@code null} for a point left of or above the canvas, an unmeasured grid, or a cell
     *         outside the text buffer
     */
    public @Nullable Point cellAt(double x, double y) {
        // SithTermFX rounds the point and the cell size to whole pixels before dividing.
        long cellWidthPixels = Math.round(cellWidth);
        long cellHeightPixels = Math.round(cellHeight);
        if (!(x >= 0) || !(y >= 0) || Double.isInfinite(x) || Double.isInfinite(y)
                || cellWidthPixels <= 0 || cellHeightPixels <= 0 || columns <= 0 || rows <= 0) {
            return null;
        }
        long column = Math.max(0, Math.min((Math.round(x) - insetX) / cellWidthPixels, columns - 1L));
        long line = Math.min(Math.round(y) / cellHeightPixels, rows - 1L) + scrollOrigin;
        if (column >= bufferWidth || line < -(long) historyLines || line >= bufferHeight) {
            return null;
        }
        return new Point((int) column, (int) line);
    }

    /**
     * Where to draw an underline under the cells {@code start} to {@code end} (inclusive, in reading
     * order, so a link wrapped over several rows covers the rest of its first row and the start of
     * its last), the way SithTermFX underlines text: from the left edge of the first cell to the
     * right edge of the last, {@value #UNDERLINE_BELOW_BASELINE} pixels below the baseline. Rows that
     * are not on screen at the current scroll origin get no segment, and columns are cut to the grid.
     *
     * @param baselineOffset the text baseline within a row, {@code TerminalPanel.getCellBaselineOffsetPixels()}
     * @return one segment per visible row, top to bottom; empty for an unmeasured grid
     */
    public @NotNull List<Segment> underline(@NotNull Point start, @NotNull Point end, double baselineOffset) {
        int lastColumn = Math.min(columns, bufferWidth) - 1;
        if (lastColumn < 0 || rows <= 0 || !(cellWidth > 0) || !(cellHeight > 0)) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        int firstLine = Math.max(start.y, scrollOrigin);
        int lastLine = Math.min(end.y, scrollOrigin + rows - 1);
        for (int line = firstLine; line <= lastLine; line++) {
            int from = Math.max(0, line == start.y ? start.x : 0);
            int to = Math.min(lastColumn, line == end.y ? end.x : lastColumn);
            if (to < from) {
                continue;
            }
            double y = (line - scrollOrigin) * cellHeight + baselineOffset + UNDERLINE_BELOW_BASELINE;
            segments.add(new Segment(insetX + from * cellWidth, insetX + (to + 1) * cellWidth, y));
        }
        return segments;
    }
}
