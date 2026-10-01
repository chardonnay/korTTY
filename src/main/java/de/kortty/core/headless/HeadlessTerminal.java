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
    private final FeedConnector connector = new FeedConnector();
    private final Thread emulatorThread;
    private final AtomicLong changes = new AtomicLong();
    private final TerminalScreenRenderer.Palette palette;

    public HeadlessTerminal(int columns, int rows) {
        this(columns, rows, TerminalScreenRenderer.Palette.DEFAULT);
    }

    public HeadlessTerminal(int columns, int rows, TerminalScreenRenderer.Palette palette) {
        this.palette = palette != null ? palette : TerminalScreenRenderer.Palette.DEFAULT;
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
     * Waits until the emulator has interpreted everything fed so far (or {@code timeoutMillis}
     * passed), so a snapshot taken right after shows the latest screen.
     */
    public void awaitProcessed(long timeoutMillis) {
        connector.awaitDrained(timeoutMillis);
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

    /** A TTY whose "remote end" is whatever {@link #feed} hands in; writes from the emulator are dropped. */
    private static final class FeedConnector implements TtyConnector {
        private final StringBuilder pending = new StringBuilder();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private boolean closed;

        void feed(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            lock.lock();
            try {
                if (!closed) {
                    pending.append(text);
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        void awaitDrained(long timeoutMillis) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            lock.lock();
            try {
                while (pending.length() > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !changed.await(remaining, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
            // the last chunk was taken; give the emulator a moment to finish interpreting it
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            lock.lock();
            try {
                while (pending.length() == 0 && !closed) {
                    changed.await();
                }
                if (pending.length() == 0) {
                    return -1;
                }
                int count = Math.min(length, pending.length());
                pending.getChars(0, count, buf, offset);
                pending.delete(0, count);
                changed.signalAll();
                return count;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } finally {
                lock.unlock();
            }
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
