package de.kortty.ui;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TtyBasedArrayDataStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.emulator.SithEmulator;
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * SithTermFX's emulator as a pane runs it (SithEmulator reading a {@link TtyBasedArrayDataStream}
 * over a connector), driven synchronously on the test thread; SithTermFX's own test doubles are not
 * published in its artifact.
 */
final class OscEmulatorHarness {

    private OscEmulatorHarness() {
    }

    /** A screen of {@code columns x rows} with {@code maxHistory} lines of scrollback. */
    static final class Screen {
        final TerminalTextBuffer buffer;
        final SithTerminal terminal;
        final CountingDisplay display = new CountingDisplay();
        final List<String> titles = new ArrayList<>();

        Screen(int columns, int rows, int maxHistory) {
            StyleState styleState = new StyleState();
            buffer = new TerminalTextBuffer(columns, rows, styleState, maxHistory, null);
            terminal = new SithTerminal(display, buffer, styleState);
            terminal.addApplicationTitleListener(titles::add);
        }

        /** Runs the emulator until {@code connector} reports the end of the stream. */
        void run(TtyConnector connector) throws IOException {
            SithEmulator emulator = new SithEmulator(new TtyBasedArrayDataStream(connector), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        /** Interprets {@code output} in one piece, without any connector. */
        void interpret(String output) throws IOException {
            SithEmulator emulator = new SithEmulator(new ArrayTerminalDataStream(output.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        /** Where the cursor stands, counted from the first line of the scrollback. */
        Position cursor() {
            return new Position(buffer.getHistoryLinesCount(), terminal.getCursorX(), terminal.getCursorY());
        }

        /** Everything the user could see or scroll to, cursor, styles, title and bells included. */
        String dump() {
            buffer.lock();
            try {
                StringBuilder out = new StringBuilder();
                for (int index = -buffer.getHistoryLinesCount(); index < buffer.getHeight(); index++) {
                    TerminalLine line = buffer.getLine(index);
                    out.append(index < 0 ? "H" : "S").append('|').append(line.getText()).append('|');
                    for (TerminalLine.TextEntry entry : line.getEntries()) {
                        out.append(entry.getLength()).append(':').append(Objects.hashCode(entry.getStyle())).append(',');
                    }
                    out.append(line.isWrapped() ? " wrapped" : "").append('\n');
                }
                out.append("cursor ").append(cursor()).append('\n');
                out.append("alternate ").append(buffer.isUsingAlternateBuffer()).append('\n');
                out.append("titles ").append(titles).append('\n');
                out.append("bells ").append(display.bells).append('\n');
                return out.toString();
            } finally {
                buffer.unlock();
            }
        }
    }

    /** A cursor position: the scrollback length, then SithTerminal's 1-based x and screen y. */
    record Position(int historyLines, int x, int y) {
    }

    /**
     * A connector whose remote end sends {@code chunks} one read each (a read shorter than a chunk
     * leaves its rest for the next), then reports the end of the stream.
     */
    static final class ScriptedConnector implements TtyConnector {
        private final ArrayDeque<String> chunks;
        private final List<String> written = new ArrayList<>();
        private boolean closed;

        ScriptedConnector(List<String> chunks) {
            this.chunks = new ArrayDeque<>(chunks);
        }

        static ScriptedConnector inChunksOf(String output, int size) {
            List<String> chunks = new ArrayList<>();
            for (int start = 0; start < output.length(); start += size) {
                chunks.add(output.substring(start, Math.min(output.length(), start + size)));
            }
            return new ScriptedConnector(chunks);
        }

        static ScriptedConnector inRandomChunks(String output, Random random, int maxSize) {
            List<String> chunks = new ArrayList<>();
            int start = 0;
            while (start < output.length()) {
                int end = Math.min(output.length(), start + 1 + random.nextInt(maxSize));
                chunks.add(output.substring(start, end));
                start = end;
            }
            return new ScriptedConnector(chunks);
        }

        @Override
        public int read(char[] buf, int offset, int length) {
            String chunk = chunks.pollFirst();
            if (chunk == null) {
                return -1;
            }
            int count = Math.min(length, chunk.length());
            chunk.getChars(0, count, buf, offset);
            if (count < chunk.length()) {
                chunks.addFirst(chunk.substring(count));
            }
            return count;
        }

        @Override
        public boolean ready() {
            return !chunks.isEmpty();
        }

        @Override
        public void write(byte[] bytes) {
            written.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void write(String string) {
            written.add(string);
        }

        List<String> written() {
            return written;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public String getName() {
            return "scripted";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Counts bells; everything else is ignored. */
    static final class CountingDisplay implements TerminalDisplay {
        int bells;

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(@Nullable CursorShape cursorShape) {
        }

        @Override
        public void beep() {
            bells++;
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
