package de.kortty.core.highlight;

import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.RequestOrigin;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.util.TermSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A SithTermFX emulator without JavaFX, built like {@code RestoredHistoryReplayTest.Session}, plus a
 * hand-driven {@link TerminalOutputHighlighter.PassScheduler}.
 */
final class HeadlessTerminalSession {

    final StyleState styleState = new StyleState();

    final TerminalTextBuffer buffer;

    final SithTerminal terminal;

    HeadlessTerminalSession(int columns, int rows) {
        this(columns, rows, 5_000);
    }

    HeadlessTerminalSession(int columns, int rows, int historyLines) {
        buffer = new TerminalTextBuffer(columns, rows, styleState, historyLines, null);
        terminal = new SithTerminal(new NoopDisplay(), buffer, styleState);
    }

    /** Writes {@code text} and moves to the start of the next line. */
    void println(String text) {
        terminal.writeUnwrappedString(text);
        terminal.carriageReturn();
        terminal.newLine();
    }

    void print(String text) {
        terminal.writeUnwrappedString(text);
    }

    void style(TextStyle style) {
        terminal.characterAttributes(style);
    }

    void resize(int columns, int rows) {
        terminal.resize(new TermSize(columns, rows), RequestOrigin.User);
    }

    int historyCount() {
        return buffer.getHistoryLinesCount();
    }

    /** Screen row {@code y} (0-based) or, for a negative {@code y}, history line {@code -y} from the bottom. */
    TerminalLine line(int y) {
        buffer.lock();
        try {
            return buffer.getLine(y);
        } finally {
            buffer.unlock();
        }
    }

    TextStyle styleAt(int x, int y) {
        buffer.lock();
        try {
            return buffer.getStyleAt(x, y);
        } finally {
            buffer.unlock();
        }
    }

    /** Every line, history first, oldest at 0. */
    List<TerminalLine> allLines() {
        buffer.lock();
        try {
            List<TerminalLine> lines = new ArrayList<>();
            for (int i = 0; i < buffer.getHistoryBuffer().getLineCount(); i++) {
                lines.add(buffer.getHistoryBuffer().getLine(i));
            }
            int screen = Math.min(buffer.getScreenBuffer().getLineCount(), buffer.getHeight());
            for (int i = 0; i < screen; i++) {
                lines.add(buffer.getScreenBuffer().getLine(i));
            }
            return lines;
        } finally {
            buffer.unlock();
        }
    }

    /** Number of cells in all lines that carry a highlight. */
    int highlightedCells() {
        int cells = 0;
        for (TerminalLine line : allLines()) {
            for (TerminalLine.TextEntry entry : line.getEntries()) {
                if (entry.getStyle() instanceof HighlightTextStyle) {
                    cells += entry.getLength();
                }
            }
        }
        return cells;
    }

    static boolean isHighlighted(TerminalLine line, int x) {
        return line.getStyleAt(x) instanceof HighlightTextStyle;
    }

    /** Runs queued passes by hand. */
    static class ManualScheduler implements TerminalOutputHighlighter.PassScheduler {

        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        private final List<Long> delays = new ArrayList<>();

        @Override
        public synchronized void schedule(Runnable task, long delayMillis) {
            queue.add(task);
            delays.add(delayMillis);
        }

        synchronized int queued() {
            return queue.size();
        }

        synchronized int scheduledTotal() {
            return delays.size();
        }

        synchronized List<Long> delays() {
            return List.copyOf(delays);
        }

        /** Runs the oldest queued pass; false when none was queued. */
        boolean runNext() {
            Runnable task;
            synchronized (this) {
                task = queue.poll();
            }
            if (task == null) {
                return false;
            }
            task.run();
            return true;
        }

        /** Runs passes until none is queued (at most {@code limit}); returns how many ran. */
        int drain(int limit) {
            int ran = 0;
            while (ran < limit && runNext()) {
                ran++;
            }
            return ran;
        }
    }

    /** Minimal display: SithTermFX's own test doubles are not published in the artifact. */
    private static final class NoopDisplay implements TerminalDisplay {

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(@Nullable CursorShape cursorShape) {
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean isCursorVisible) {
        }

        @Override
        public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
        }

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(@NotNull String windowTitle) {
        }

        @Override
        public @Nullable TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(@NotNull MouseMode mouseMode) {
        }

        @Override
        public void setMouseFormat(@NotNull MouseFormat mouseFormat) {
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
