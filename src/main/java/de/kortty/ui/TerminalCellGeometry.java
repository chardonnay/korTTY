package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import org.jetbrains.annotations.Nullable;

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
}
