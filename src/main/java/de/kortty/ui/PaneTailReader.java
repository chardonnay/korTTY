package de.kortty.ui;

import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the newest rows of one terminal pane — the end of its scrollback and its screen — for the
 * session snapshot's saved output ({@link de.kortty.core.SessionScrollbackStore}).
 *
 * <p>Bounded: it walks back from the last row and touches at most {@code maxLines} rows, plus the
 * rows a wrapped line continues over (at most {@value #ROW_BUDGET_FACTOR} rows per line in all),
 * never the whole history. The buffer lock it holds is held for that many rows only, so the
 * emulator thread waits no longer for a pane with a long history.
 * Rows the emulator wrapped because they were wider than the pane are joined back into one line,
 * so the saved output wraps anew at the width of the pane it is shown in.
 *
 * <p>While a full-screen program uses the alternate screen (an editor, a pager) the pane's normal
 * rows are not reachable; the read returns {@code null} and the previously saved output stays.
 *
 * <p>Any thread; toolkit-free.
 */
final class PaneTailReader {

    /**
     * At most this many rows per requested line are touched: a line wrapped over thousands of rows
     * does not make the read walk the whole history.
     */
    static final int ROW_BUDGET_FACTOR = 4;

    private PaneTailReader() {
    }

    /**
     * The newest rows of {@code buffer}, oldest first, without the blank rows below the last
     * output.
     *
     * @param maxLines the most lines returned; 0 or less returns none
     * @return the lines, or {@code null} while the alternate screen is in use
     */
    static @Nullable List<String> readTail(@Nullable TerminalTextBuffer buffer, int maxLines) {
        if (buffer == null || maxLines <= 0) {
            return List.of();
        }
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return null;
            }
            int history = buffer.getHistoryLinesCount();
            int lastRow = lastNonBlankRow(buffer);
            List<String> newestFirst = new ArrayList<>(Math.min(maxLines, history + lastRow + 1));
            // Pieces of the line being joined, newest piece first.
            List<String> pieces = new ArrayList<>();
            long budget = (long) maxLines * ROW_BUDGET_FACTOR;
            // Row indexes as TerminalTextBuffer.getLine takes them: history rows are negative.
            for (int row = lastRow; row >= -history && newestFirst.size() < maxLines && budget-- > 0; row--) {
                pieces.add(buffer.getLine(row).getText());
                TerminalLine previous = row - 1 >= -history ? buffer.getLine(row - 1) : null;
                if (previous != null && previous.isWrapped()) {
                    // The row above continues on this one: the same line.
                    continue;
                }
                newestFirst.add(join(pieces));
                pieces.clear();
            }
            if (!pieces.isEmpty() && newestFirst.size() < maxLines) {
                newestFirst.add(join(pieces));
            }
            Collections.reverse(newestFirst);
            return newestFirst;
        } finally {
            buffer.unlock();
        }
    }

    /** The last screen row with text, or -1 when the screen is blank (only history is read then). */
    private static int lastNonBlankRow(TerminalTextBuffer buffer) {
        for (int row = buffer.getHeight() - 1; row >= 0; row--) {
            if (!buffer.getLine(row).getText().isBlank()) {
                return row;
            }
        }
        return -1;
    }

    /** The pieces of one wrapped line, collected newest first, as the line. */
    private static String join(List<String> newestFirst) {
        StringBuilder line = new StringBuilder();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            line.append(newestFirst.get(i));
        }
        return line.toString().stripTrailing();
    }
}
