package de.kortty.core.headless;

import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TtyBasedArrayDataStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.core.TerminalRecordingScreenSnapshot;
import de.kortty.core.TerminalScreenRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A terminal emulator without a window: the output of a command running in a PTY is fed in with
 * {@link #feed}, a background thread interprets its escape sequences into a
 * {@link TerminalTextBuffer} exactly as a terminal tab would, and {@link #snapshot()} reads the
 * current screen — for screenshots of full-screen programs run by a JobScheduler job, without
 * opening anything the user could see.
 */
public final class HeadlessTerminal implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HeadlessTerminal.class);
    private static final int HISTORY_LINES = 2_000;

    private final TerminalTextBuffer buffer;
    private final FeedConnector connector;
    private final Thread emulatorThread;
    private final AtomicLong changes = new AtomicLong();
    private final TerminalScreenRenderer.Palette palette;

    public HeadlessTerminal(int columns, int rows) {
        this(columns, rows, TerminalScreenRenderer.Palette.DEFAULT);
    }

    public HeadlessTerminal(int columns, int rows, TerminalScreenRenderer.Palette palette) {
        this(columns, rows, palette, null);
    }

    /**
     * {@code onInputTaken} runs on the emulator thread each time it has taken a chunk of input and
     * before it interprets it — tests use it to stand in for an emulator thread that is slow to
     * get scheduled.
     */
    HeadlessTerminal(int columns, int rows, TerminalScreenRenderer.Palette palette, Runnable onInputTaken) {
        this.palette = palette != null ? palette : TerminalScreenRenderer.Palette.DEFAULT;
        connector = new FeedConnector(onInputTaken);
        StyleState styleState = new StyleState();
        buffer = new TerminalTextBuffer(Math.max(10, columns), Math.max(3, rows), styleState, HISTORY_LINES, null);
        buffer.addModelListener(changes::incrementAndGet);
        SithTerminal terminal = new SithTerminal(new NoDisplay(), buffer, styleState);
        SithEmulator emulator = new SithEmulator(new TtyBasedArrayDataStream(connector), terminal);
        emulatorThread = new Thread(() -> {
            try {
                while (emulator.hasNext()) {
                    emulator.next();
                }
            } catch (IOException e) {
                // EOF after close(): the stream is done
            } catch (RuntimeException e) {
                logger.debug("Headless terminal emulator stopped: {}", e.getMessage());
            } finally {
                connector.emulatorStopped();
            }
        }, "HeadlessTerminal-Emulator");
        emulatorThread.setDaemon(true);
        emulatorThread.start();
    }

    /** Output decoded from the PTY, escape sequences included. */
    public void feed(String output) {
        connector.feed(output);
    }

    /** Increases whenever the screen content changed; compare two readings to detect activity. */
    public long changeCount() {
        return changes.get();
    }

    public int columns() {
        return buffer.getWidth();
    }

    public int rows() {
        return buffer.getHeight();
    }

    /**
     * Waits until the emulator has interpreted everything fed before this call, so a snapshot taken
     * right after shows the latest screen. Output fed concurrently while waiting does not extend
     * the wait; an escape sequence cut off at the end of the input stays pending until the rest of
     * it is fed, exactly as in a terminal tab.
     *
     * @return {@code true} once that output is on the screen; {@code false} if {@code timeoutMillis}
     *         passed first, the waiting thread was interrupted, or the emulator has stopped — the
     *         screen may then lag behind the output
     */
    public boolean awaitProcessed(long timeoutMillis) {
        return connector.awaitProcessed(timeoutMillis);
    }

    /** The visible screen with colours. */
    public TerminalRecordingScreenSnapshot snapshot() {
        return TerminalScreenRenderer.snapshot(buffer, palette, true);
    }

    /** The visible screen as plain text lines. */
    public String screenText() {
        buffer.lock();
        try {
            return buffer.getScreenLines();
        } finally {
            buffer.unlock();
        }
    }

    /** Stops the emulator after it has interpreted everything fed so far. */
    @Override
    public void close() {
        connector.close();
        try {
            emulatorThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A TTY whose "remote end" is whatever {@link #feed} hands in; writes from the emulator are dropped.
     *
     * <p>Progress is counted in chars: {@code fed} by {@link #feed}, {@code handedOut} to the
     * emulator by {@link #read}, and {@code processed} — interpreted onto the screen. The emulator
     * only calls {@link #read} again once its own buffer is used up, i.e. after it has interpreted
     * every char of the previous read, so entering {@link #read} is what advances
     * {@code processed}. An empty {@code pending} buffer alone only means the emulator has
     * <em>taken</em> the last chunk, not that it is on the screen yet.
     */
    private static final class FeedConnector implements TtyConnector {
        private final StringBuilder pending = new StringBuilder();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition inputAvailable = lock.newCondition();
        private final Condition progressed = lock.newCondition();
        private final Runnable onInputTaken;
        private long fed;
        private long handedOut;
        private long processed;
        private boolean closed;
        private boolean emulatorStopped;

        FeedConnector(Runnable onInputTaken) {
            this.onInputTaken = onInputTaken;
        }

        void feed(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            lock.lock();
            try {
                if (!closed) {
                    pending.append(text);
                    fed += text.length();
                    inputAvailable.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        boolean awaitProcessed(long timeoutMillis) {
            long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            lock.lock();
            try {
                long target = fed;
                while (processed < target) {
                    if (emulatorStopped || remaining <= 0) {
                        return false;
                    }
                    remaining = progressed.awaitNanos(remaining);
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                lock.unlock();
            }
        }

        void emulatorStopped() {
            lock.lock();
            try {
                emulatorStopped = true;
                progressed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
                inputAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            int count;
            lock.lock();
            try {
                // everything handed out before is interpreted, or the emulator would not ask for more
                processed = handedOut;
                progressed.signalAll();
                while (pending.length() == 0 && !closed) {
                    inputAvailable.await();
                }
                if (pending.length() == 0) {
                    return -1;
                }
                count = Math.min(length, pending.length());
                pending.getChars(0, count, buf, offset);
                pending.delete(0, count);
                handedOut += count;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } finally {
                lock.unlock();
            }
            if (onInputTaken != null) {
                onInputTaken.run();
            }
            return count;
        }

        @Override
        public void write(byte[] bytes) {
            // device-status answers of the emulator have nowhere to go
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            lock.lock();
            try {
                return !closed || pending.length() > 0;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            lock.lock();
            try {
                return pending.length() > 0;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String getName() {
            return "headless";
        }

    }

    /** Everything a terminal would show or ring is ignored — there is no screen. */
    private static final class NoDisplay implements TerminalDisplay {
        private volatile String title = "";

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(CursorShape shape) {
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean visible) {
        }

        @Override
        public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
        }

        @Override
        public String getWindowTitle() {
            return title;
        }

        @Override
        public void setWindowTitle(String windowTitle) {
            title = windowTitle != null ? windowTitle : "";
        }

        @Override
        public TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {
        }

        @Override
        public void setMouseFormat(MouseFormat format) {
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
