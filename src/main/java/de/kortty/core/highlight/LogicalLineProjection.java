package de.kortty.core.highlight;

import java.util.Arrays;
import java.util.List;

/**
 * The text a highlight rule is matched against for one logical line, and the way back from a
 * character of that text to the terminal cells it occupies.
 *
 * <p>A terminal row's text is one character per cell, which is not what a pattern should see:
 * <ul>
 *   <li>A wide (CJK, emoji) character is followed by a {@code U+E000} placeholder for its second
 *       cell, so {@code 東京} is stored as four characters and a pattern for it never matches.</li>
 *   <li>Unwritten cells are {@code NUL}, and padding a soft-wrapped row with them would put
 *       characters into the middle of a word that the program printed in one piece.</li>
 *   <li>A line longer than the terminal is soft-wrapped over several rows, and a match can span
 *       the wrap.</li>
 * </ul>
 * The projection joins the rows of one logical line, leaves out every placeholder and {@code NUL},
 * and remembers for each projected character its row, its column and how many cells it covers, so a
 * placeholder cell takes the owner of the wide character in front of it.
 *
 * <p>Bounded: at most {@value #MAX_ROWS} rows and {@value #MAX_CHARS} projected characters; the rest
 * is cut off and {@link #truncated()} says so. Pure, no terminal types; any thread.
 */
public final class LogicalLineProjection {

    /** Most rows one logical line may join. */
    public static final int MAX_ROWS = 64;

    /** Most characters a logical line projects to; also the matcher's input cap. */
    public static final int MAX_CHARS = 8_192;

    /** The second cell of a double-width character ({@code CharUtils.DWC} in SithTermFX). */
    public static final char WIDE_CHAR_PLACEHOLDER = '';

    private static final char NUL = '\0';

    private final String text;

    private final int[] rows;

    private final int[] columns;

    private final int[] cells;

    private final int rowsUsed;

    private final boolean truncated;

    private LogicalLineProjection(String text, int[] rows, int[] columns, int[] cells, int rowsUsed,
                                  boolean truncated) {
        this.text = text;
        this.rows = rows;
        this.columns = columns;
        this.cells = cells;
        this.rowsUsed = rowsUsed;
        this.truncated = truncated;
    }

    /**
     * Projects the rows of one logical line: every row but the last is soft-wrapped into the next.
     * Index {@code x} of a row's text is cell {@code x}.
     */
    public static LogicalLineProjection of(List<? extends CharSequence> rowTexts) {
        int rowLimit = Math.min(rowTexts.size(), MAX_ROWS);
        boolean truncated = rowTexts.size() > MAX_ROWS;
        int capacity = 0;
        for (int row = 0; row < rowLimit; row++) {
            CharSequence rowText = rowTexts.get(row);
            capacity += rowText != null ? rowText.length() : 0;
        }
        capacity = Math.min(capacity, MAX_CHARS);
        StringBuilder projected = new StringBuilder(capacity);
        int[] rowOf = new int[capacity];
        int[] columnOf = new int[capacity];
        int[] cellsOf = new int[capacity];
        int rowsUsed = 0;
        outer:
        for (int row = 0; row < rowLimit; row++) {
            CharSequence rowText = rowTexts.get(row);
            rowsUsed = row + 1;
            if (rowText == null) {
                continue;
            }
            for (int x = 0; x < rowText.length(); x++) {
                char c = rowText.charAt(x);
                if (c == WIDE_CHAR_PLACEHOLDER) {
                    int last = projected.length() - 1;
                    // The placeholder belongs to the character right in front of it on the same row.
                    if (last >= 0 && rowOf[last] == row && columnOf[last] + cellsOf[last] == x) {
                        cellsOf[last]++;
                    }
                    continue;
                }
                if (c == NUL) {
                    continue;
                }
                if (projected.length() >= MAX_CHARS) {
                    truncated = true;
                    break outer;
                }
                int index = projected.length();
                projected.append(c);
                rowOf[index] = row;
                columnOf[index] = x;
                cellsOf[index] = 1;
            }
        }
        int length = projected.length();
        return new LogicalLineProjection(projected.toString(),
            Arrays.copyOf(rowOf, length), Arrays.copyOf(columnOf, length), Arrays.copyOf(cellsOf, length),
            rowsUsed, truncated);
    }

    /** The text to match: no placeholders, no {@code NUL}s, wrapped rows joined. */
    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /** Row of the projected character, counted from the first row handed to {@link #of(List)}. */
    public int row(int index) {
        return rows[index];
    }

    /** Cell column of the projected character within its row. */
    public int column(int index) {
        return columns[index];
    }

    /** Cells the character covers: 1, or 2 and more for a wide character and its placeholders. */
    public int cells(int index) {
        return cells[index];
    }

    /** How many of the handed rows were read (at most {@value #MAX_ROWS}). */
    public int rowsUsed() {
        return rowsUsed;
    }

    /** True when rows or characters were cut off by the caps. */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Spreads per-character owners (as {@link HighlightMatcher} reports them) onto the cells of one
     * row: every cell a projected character covers, placeholders included, gets that character's
     * owner; every other cell gets {@link HighlightMatcher#NO_OWNER}.
     *
     * @param owners one owner per projected character, {@link #length()} long
     * @param row the row, as counted by {@link #row(int)}
     * @param rowLength the number of cells of that row
     */
    public int[] cellOwners(int[] owners, int row, int rowLength) {
        int[] result = new int[Math.max(0, rowLength)];
        Arrays.fill(result, HighlightMatcher.NO_OWNER);
        int limit = Math.min(owners.length, text.length());
        // Rows only grow along the projection, so the row's characters are one contiguous run.
        for (int index = firstIndexOfRow(row); index < limit && rows[index] == row; index++) {
            int end = Math.min(columns[index] + cells[index], result.length);
            for (int x = columns[index]; x < end; x++) {
                result[x] = owners[index];
            }
        }
        return result;
    }

    /** The first projected index on {@code row}, or {@link #length()} when the row projects nothing. */
    private int firstIndexOfRow(int row) {
        int low = 0;
        int high = rows.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rows[mid] < row) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
