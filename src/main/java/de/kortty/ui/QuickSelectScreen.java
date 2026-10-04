package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.util.CharUtils;
import de.kortty.core.QuickSelectPatterns;
import de.kortty.core.TerminalLinkDetector;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.core.TerminalLinkDetector.Match;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What quick select can pick on the rows a terminal pane shows: every URL, path, e-mail address,
 * UUID, IP address, git hash and number of four or more digits ({@link TerminalLinkDetector}), and
 * every match of the user's own patterns ({@link QuickSelectPatterns}).
 *
 * <p>{@link #capture} reads the visible rows once, at the scroll origin the pane shows (in the
 * history while scrolled back), joins the rows that wrap into each other so a match that wraps is
 * found whole, and keeps the cells of every match so the pane can mark them. A match that runs on
 * above the first or below the last visible row is left out rather than offered cut short. The text
 * of each match is captured with it, so copying or opening it uses the text that was on screen,
 * whatever the program prints meanwhile; {@link #stillShows} tells whether the cells still hold it.
 *
 * <p>Toolkit-free. It reads the buffer under {@link TerminalTextBuffer#lock()}, because the emulator
 * thread writes to it while the JavaFX thread asks.
 */
public final class QuickSelectScreen {

    /** Every kind {@link TerminalLinkDetector} knows: quick select offers them all. */
    public static final Set<Kind> ALL_KINDS = Collections.unmodifiableSet(EnumSet.allOf(Kind.class));

    /**
     * One match on screen.
     *
     * @param kind  what the text is
     * @param text  the text as shown, without {@link CharUtils#DWC} cells
     * @param start the first cell ({@code y} is the buffer line, negative in the history)
     * @param end   the last cell, inclusive
     */
    public record Hit(@NotNull Kind kind, @NotNull String text, @NotNull Point start, @NotNull Point end) {

        public Hit {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            start = new Point(Objects.requireNonNull(start, "start"));
            end = new Point(Objects.requireNonNull(end, "end"));
        }

        @Override
        public @NotNull Point start() {
            return new Point(start);
        }

        @Override
        public @NotNull Point end() {
            return new Point(end);
        }
    }

    private QuickSelectScreen() {
    }

    /**
     * The matches on the {@code rows} rows from buffer line {@code scrollOrigin} down, in reading
     * order. Rows outside the buffer are skipped.
     *
     * @param scrollOrigin the buffer line in the pane's top row; negative while scrolled back
     * @param rows         the rows the pane shows
     */
    public static @NotNull List<Hit> capture(@NotNull TerminalTextBuffer buffer, int scrollOrigin, int rows) {
        return capture(buffer, scrollOrigin, rows, QuickSelectPatterns.NONE);
    }

    /**
     * {@link #capture(TerminalTextBuffer, int, int)} plus the matches of the user's own
     * {@code patterns} as {@link Kind#CUSTOM}, found in the same rows and overlapping the built-in
     * ones as {@link TerminalLinkDetector#find(CharSequence, Set, boolean, boolean, QuickSelectPatterns.Matching)}
     * decides. All patterns of this capture share one time budget ({@link QuickSelectPatterns}).
     */
    public static @NotNull List<Hit> capture(@NotNull TerminalTextBuffer buffer, int scrollOrigin, int rows,
            @NotNull QuickSelectPatterns patterns) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(patterns, "patterns");
        buffer.lock();
        try {
            // The budget starts once the buffer is ours: waiting for the emulator thread is no matching.
            QuickSelectPatterns.Matching custom = patterns.isEmpty() ? null : patterns.start();
            int firstBufferLine = -buffer.getHistoryLinesCount();
            int lastBufferLine = buffer.getHeight() - 1;
            int first = Math.max(scrollOrigin, firstBufferLine);
            int last = Math.min(scrollOrigin + rows - 1, lastBufferLine);
            int width = buffer.getWidth();
            List<Hit> hits = new ArrayList<>();
            if (width <= 0 || rows <= 0) {
                return hits;
            }
            // A single detector pass reads at most MAX_INPUT_CHARS, so a long logical line goes in slices.
            int rowsPerSlice = Math.max(1, TerminalLinkDetector.MAX_INPUT_CHARS / width);
            int row = first;
            while (row <= last) {
                int lineStart = row;
                int lineEnd = row;
                while (lineEnd < last && buffer.getLine(lineEnd).isWrapped()) {
                    lineEnd++;
                }
                boolean continuesBefore = lineStart == first && lineStart > firstBufferLine
                    && buffer.getLine(lineStart - 1).isWrapped();
                boolean continuesAfter = lineEnd == last && lineEnd < lastBufferLine
                    && buffer.getLine(lineEnd).isWrapped();
                for (int sliceStart = lineStart; sliceStart <= lineEnd; sliceStart += rowsPerSlice) {
                    int sliceEnd = Math.min(lineEnd, sliceStart + rowsPerSlice - 1);
                    TerminalLinkResolver.Window window = new TerminalLinkResolver.Window(sliceStart, sliceEnd, width,
                        sliceStart > lineStart || continuesBefore, sliceEnd < lineEnd || continuesAfter);
                    for (Match match : TerminalLinkDetector.find(window.text(buffer), ALL_KINDS,
                            window.continuesBefore(), window.continuesAfter(), custom)) {
                        hits.add(new Hit(match.kind(), match.text(), window.cellAt(match.start()),
                            window.cellAt(match.end() - 1)));
                    }
                }
                row = lineEnd + 1;
            }
            return hits;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * Whether the cells of {@code hit} still hold its text, so its label still marks what it copies.
     * False once the program printed over it, the screen scrolled it away, or the buffer shrank.
     */
    public static boolean stillShows(@NotNull TerminalTextBuffer buffer, @NotNull Hit hit) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(hit, "hit");
        Point start = hit.start();
        Point end = hit.end();
        buffer.lock();
        try {
            int width = buffer.getWidth();
            if (start.y < -buffer.getHistoryLinesCount() || end.y >= buffer.getHeight() || end.y < start.y
                    || start.x < 0 || end.x >= width) {
                return false;
            }
            String cells = new TerminalLinkResolver.Window(start.y, end.y, width, false, false).text(buffer);
            int from = start.x;
            int to = (end.y - start.y) * width + end.x;
            StringBuilder shown = new StringBuilder(to - from + 1);
            for (int i = from; i <= to && i < cells.length(); i++) {
                char c = cells.charAt(i);
                if (c != CharUtils.DWC) {
                    // As the detector saw it: an empty cell inside a match of the user's pattern is a space.
                    shown.append(TerminalLinkDetector.asMatched(c));
                }
            }
            return shown.toString().equals(hit.text());
        } finally {
            buffer.unlock();
        }
    }
}
