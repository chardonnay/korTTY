package de.kortty.ui;

/**
 * KI-Agent design 20, the thinking dock: picks the status display by the terminal's size. A wide and
 * tall terminal gets the side timeline (02), a small one the status pill (05), anything between the
 * Powerline bar (01). Once chosen, a display is kept until the size is clearly past the other side of
 * its threshold, so dragging a split across a threshold does not make the dock flicker. Pure logic.
 */
final class AgentAdaptiveDock {

    static final String SIDE = "d02";
    static final String BAR = "d01";
    static final String PILL = "d05";

    /** From this many columns and rows the terminal has room for the side timeline. */
    static final int SIDE_COLUMNS = 150;
    static final int SIDE_ROWS = 30;
    /** Below this many columns or rows only the pill fits. */
    static final int PILL_COLUMNS = 70;
    static final int PILL_ROWS = 16;
    /** How far past a threshold the size must go before the dock changes its mind. */
    static final int COLUMN_HYSTERESIS = 8;
    static final int ROW_HYSTERESIS = 3;

    private String current = BAR;

    /** The display chosen last; the bar before anything was measured. */
    String current() {
        return current;
    }

    /** The display for a terminal of {@code columns} × {@code rows}, remembering it for the next call. */
    String choose(int columns, int rows) {
        if (columns <= 0 || rows <= 0) {
            return current;
        }
        current = switch (current) {
            case SIDE -> columns < SIDE_COLUMNS - COLUMN_HYSTERESIS || rows < SIDE_ROWS - ROW_HYSTERESIS
                ? fresh(columns, rows) : SIDE;
            case PILL -> columns >= PILL_COLUMNS + COLUMN_HYSTERESIS && rows >= PILL_ROWS + ROW_HYSTERESIS
                ? fresh(columns, rows) : PILL;
            default -> fresh(columns, rows);
        };
        return current;
    }

    /** The display for a size, without hysteresis. */
    static String fresh(int columns, int rows) {
        if (columns < PILL_COLUMNS || rows < PILL_ROWS) {
            return PILL;
        }
        if (columns >= SIDE_COLUMNS && rows >= SIDE_ROWS) {
            return SIDE;
        }
        return BAR;
    }
}
