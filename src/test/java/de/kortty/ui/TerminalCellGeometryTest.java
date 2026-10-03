package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pixel-to-cell mapping for korTTY's terminal mouse hooks. It has to agree with SithTermFX's own
 * {@code TerminalPanel.panelPointToCell} (inset, whole-pixel rounding, clamping onto the last
 * column and row, the scroll origin) and with korTTY's pinned bottom-row patch, which rejects
 * {@code line == height}. The live comparison runs in {@code TerminalContextMenuActionsSmoke}.
 *
 * <p>The other direction, cells to pixels, places the hover underline of a link exactly where
 * SithTermFX draws its own underline; {@code terminalLinksSmoke} checks it in a real pane.
 */
public class TerminalCellGeometryTest {

    private static final int INSET = 4;
    private static final int CELL_WIDTH = 8;
    private static final int CELL_HEIGHT = 16;
    private static final int COLUMNS = 80;
    private static final int ROWS = 24;
    private static final int HISTORY = 100;
    private static final double BASELINE = 12.5;

    /** An 80x24 screen with 100 history lines, scrolled to the bottom. */
    private static final TerminalCellGeometry SCREEN = scrolledTo(0);

    @Test
    public void theInsetBelongsToTheFirstColumnAndEachCellIsOneCellSizeWide() {
        assertThat(SCREEN.cellAt(0, 0)).isEqualTo(cell(0, 0));
        assertThat(SCREEN.cellAt(INSET - 1, 0)).isEqualTo(cell(0, 0));
        assertThat(SCREEN.cellAt(INSET + CELL_WIDTH - 1, 0)).isEqualTo(cell(0, 0));
        assertThat(SCREEN.cellAt(INSET + CELL_WIDTH, 0)).isEqualTo(cell(1, 0));
        assertThat(SCREEN.cellAt(INSET + CELL_WIDTH * (COLUMNS - 1), 0)).isEqualTo(cell(COLUMNS - 1, 0));

        assertThat(SCREEN.cellAt(INSET, CELL_HEIGHT - 1)).isEqualTo(cell(0, 0));
        assertThat(SCREEN.cellAt(INSET, CELL_HEIGHT)).isEqualTo(cell(0, 1));
    }

    @Test
    public void thePointAndTheCellSizeAreRoundedToWholePixelsLikeSithTermFx() {
        assertThat(SCREEN.cellAt(INSET + CELL_WIDTH - 0.51, CELL_HEIGHT - 0.51)).isEqualTo(cell(0, 0));
        assertThat(SCREEN.cellAt(INSET + CELL_WIDTH - 0.5, CELL_HEIGHT - 0.5)).isEqualTo(cell(1, 1));

        // A 7.6 x 15.5 cell counts as 8 x 16.
        TerminalCellGeometry fractional = new TerminalCellGeometry(
            INSET, 7.6, 15.5, COLUMNS, ROWS, 0, COLUMNS, ROWS, HISTORY);
        assertThat(fractional.cellAt(INSET + 8, 16)).isEqualTo(cell(1, 1));
        assertThat(fractional.cellAt(INSET + 7, 15)).isEqualTo(cell(0, 0));
    }

    @Test
    public void rowsScrolledBackIntoTheHistoryMapOntoNegativeLines() {
        TerminalCellGeometry scrolledBack = scrolledTo(-10);
        assertThat(scrolledBack.cellAt(INSET, 0)).isEqualTo(cell(0, -10));
        assertThat(scrolledBack.cellAt(INSET, CELL_HEIGHT * 9)).isEqualTo(cell(0, -1));
        assertThat(scrolledBack.cellAt(INSET, CELL_HEIGHT * 10)).isEqualTo(cell(0, 0));

        assertThat(scrolledTo(-HISTORY).cellAt(INSET, 0)).isEqualTo(cell(0, -HISTORY));
    }

    @Test
    public void pointsBelowTheLastRowOrRightOfTheLastColumnLandOnThatRowOrColumn() {
        // The last row is line height - 1; the pinned patch exists because line == height slipped through.
        assertThat(SCREEN.cellAt(INSET, 10_000)).isEqualTo(cell(0, ROWS - 1));
        assertThat(SCREEN.cellAt(10_000, 0)).isEqualTo(cell(COLUMNS - 1, 0));
        assertThat(SCREEN.cellAt(1e12, 1e12)).isEqualTo(cell(COLUMNS - 1, ROWS - 1));
        assertThat(scrolledTo(-5).cellAt(INSET, 10_000)).isEqualTo(cell(0, ROWS - 1 - 5));
    }

    @Test
    public void aLaidOutRowOrColumnTheTextBufferDoesNotHaveYetIsNoCell() {
        // During a resize the panel can lay out one more row or column than the buffer has yet.
        TerminalCellGeometry taller = new TerminalCellGeometry(
            INSET, CELL_WIDTH, CELL_HEIGHT, COLUMNS, ROWS + 1, 0, COLUMNS, ROWS, HISTORY);
        assertThat(taller.cellAt(INSET, CELL_HEIGHT * (ROWS - 1))).isEqualTo(cell(0, ROWS - 1));
        assertThat(taller.cellAt(INSET, CELL_HEIGHT * ROWS)).isNull();
        assertThat(taller.cellAt(INSET, 10_000)).isNull();

        TerminalCellGeometry wider = new TerminalCellGeometry(
            INSET, CELL_WIDTH, CELL_HEIGHT, COLUMNS + 1, ROWS, 0, COLUMNS, ROWS, HISTORY);
        assertThat(wider.cellAt(INSET + CELL_WIDTH * (COLUMNS - 1), 0)).isEqualTo(cell(COLUMNS - 1, 0));
        assertThat(wider.cellAt(INSET + CELL_WIDTH * COLUMNS, 0)).isNull();
    }

    @Test
    public void pointsOffTheCanvasOrAboveTheHistoryAreNoCell() {
        assertThat(SCREEN.cellAt(-1, 0)).isNull();
        assertThat(SCREEN.cellAt(0, -1)).isNull();
        assertThat(SCREEN.cellAt(-0.4, 0)).isNull();
        assertThat(SCREEN.cellAt(Double.NaN, 0)).isNull();
        assertThat(SCREEN.cellAt(0, Double.NaN)).isNull();
        assertThat(SCREEN.cellAt(Double.POSITIVE_INFINITY, 0)).isNull();
        assertThat(SCREEN.cellAt(0, Double.POSITIVE_INFINITY)).isNull();

        // A scroll origin above the oldest history line, for example after the history shrank.
        TerminalCellGeometry pastTheHistory = scrolledTo(-HISTORY - 1);
        assertThat(pastTheHistory.cellAt(INSET, 0)).isNull();
        assertThat(pastTheHistory.cellAt(INSET, CELL_HEIGHT)).isEqualTo(cell(0, -HISTORY));
    }

    @Test
    public void anUnmeasuredGridHasNoCells() {
        assertThat(new TerminalCellGeometry(INSET, 0, CELL_HEIGHT, COLUMNS, ROWS, 0, COLUMNS, ROWS, HISTORY)
            .cellAt(INSET, 0)).isNull();
        assertThat(new TerminalCellGeometry(INSET, CELL_WIDTH, 0.4, COLUMNS, ROWS, 0, COLUMNS, ROWS, HISTORY)
            .cellAt(INSET, 0)).isNull();
        assertThat(new TerminalCellGeometry(INSET, CELL_WIDTH, CELL_HEIGHT, 0, ROWS, 0, COLUMNS, ROWS, HISTORY)
            .cellAt(INSET, 0)).isNull();
        assertThat(new TerminalCellGeometry(INSET, CELL_WIDTH, CELL_HEIGHT, COLUMNS, 0, 0, COLUMNS, ROWS, HISTORY)
            .cellAt(INSET, 0)).isNull();
    }

    @Test
    public void anUnderlineRunsFromTheLeftEdgeOfTheFirstCellToTheRightEdgeOfTheLastBelowTheBaseline() {
        List<TerminalCellGeometry.Segment> underline = SCREEN.underline(cell(2, 3), cell(5, 3), BASELINE);

        assertThat(underline).containsExactly(new TerminalCellGeometry.Segment(
            INSET + 2 * CELL_WIDTH, INSET + 6 * CELL_WIDTH, 3 * CELL_HEIGHT + BASELINE + 3));
    }

    @Test
    public void aWrappedLinkGetsOneSegmentPerRow() {
        List<TerminalCellGeometry.Segment> underline = SCREEN.underline(cell(76, 3), cell(3, 5), BASELINE);

        assertThat(underline).containsExactly(
            new TerminalCellGeometry.Segment(INSET + 76 * CELL_WIDTH, INSET + COLUMNS * CELL_WIDTH,
                3 * CELL_HEIGHT + BASELINE + 3),
            new TerminalCellGeometry.Segment(INSET, INSET + COLUMNS * CELL_WIDTH, 4 * CELL_HEIGHT + BASELINE + 3),
            new TerminalCellGeometry.Segment(INSET, INSET + 4 * CELL_WIDTH, 5 * CELL_HEIGHT + BASELINE + 3))
            .inOrder();
    }

    @Test
    public void onlyRowsOnScreenAtTheScrollOriginGetASegment() {
        TerminalCellGeometry scrolledBack = scrolledTo(-10);

        // History line -10 is the top row now; line -11 is above the screen.
        assertThat(scrolledBack.underline(cell(70, -11), cell(4, -10), BASELINE)).containsExactly(
            new TerminalCellGeometry.Segment(INSET, INSET + 5 * CELL_WIDTH, BASELINE + 3));
        // The screen shows lines -10 to 13, so line 14 is below it.
        assertThat(scrolledBack.underline(cell(0, 14), cell(9, 14), BASELINE)).isEmpty();
        assertThat(SCREEN.underline(cell(0, -1), cell(9, -1), BASELINE)).isEmpty();
    }

    @Test
    public void anUnderlineIsCutToTheGrid() {
        TerminalCellGeometry narrow = new TerminalCellGeometry(
            INSET, CELL_WIDTH, CELL_HEIGHT, 20, ROWS, 0, COLUMNS, ROWS, HISTORY);

        assertThat(narrow.underline(cell(15, 0), cell(30, 0), BASELINE)).containsExactly(
            new TerminalCellGeometry.Segment(INSET + 15 * CELL_WIDTH, INSET + 20 * CELL_WIDTH, BASELINE + 3));
        assertThat(narrow.underline(cell(25, 0), cell(30, 0), BASELINE)).isEmpty();
    }

    @Test
    public void anUnmeasuredGridHasNoUnderline() {
        assertThat(new TerminalCellGeometry(INSET, 0, CELL_HEIGHT, COLUMNS, ROWS, 0, COLUMNS, ROWS, HISTORY)
            .underline(cell(0, 0), cell(3, 0), BASELINE)).isEmpty();
        assertThat(new TerminalCellGeometry(INSET, CELL_WIDTH, CELL_HEIGHT, 0, ROWS, 0, COLUMNS, ROWS, HISTORY)
            .underline(cell(0, 0), cell(3, 0), BASELINE)).isEmpty();
    }

    private static TerminalCellGeometry scrolledTo(int scrollOrigin) {
        return new TerminalCellGeometry(
            INSET, CELL_WIDTH, CELL_HEIGHT, COLUMNS, ROWS, scrollOrigin, COLUMNS, ROWS, HISTORY);
    }

    private static Point cell(int column, int line) {
        return new Point(column, line);
    }
}
