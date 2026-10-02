package de.kortty.ui;

import com.sithtermfx.core.model.TerminalTextBuffer;

import java.util.Objects;

/**
 * Tells how many lines the terminal dropped from the top of its scrollback since the last look.
 *
 * <p>Command timestamps are keyed by absolute line: the history line count plus the cursor row.
 * That key stays put while the history grows, but once the history reaches its limit every new
 * line pushes the oldest one out and the count stays constant. Without knowing how many lines
 * fell off, every mark keeps a key that now names a later line, and every new prompt on the
 * bottom row computes the key of the previous one.
 *
 * <p>The tracker anchors on the identity of the newest history line. The emulator moves line
 * objects from the screen into the history and trims the history from its top without copying
 * them, so the anchor's new index says exactly how far everything moved. It reports:
 * <ul>
 *   <li>{@link Trim.Kind#SHIFT}: the anchor is {@code n} rows higher; {@code n} lines were trimmed
 *       (zero while the history is still growing);</li>
 *   <li>{@link Trim.Kind#CLEARED}: the history went from lines to none (Clear Buffer, {@code ESC[3J},
 *       reset), or - while the history is still empty, on a terminal that has not scrolled yet -
 *       the screen went from lines to none, which is what Clear Buffer does to it;</li>
 *   <li>{@link Trim.Kind#UNKNOWN}: the anchor is gone, for example after a width reflow rebuilt
 *       every line; the tracker re-anchors without claiming a shift;</li>
 *   <li>{@link Trim.Kind#SUSPENDED}: the alternate screen is active and the primary history is
 *       swapped out, so nothing is measured and the anchor is kept for the return.</li>
 * </ul>
 *
 * <p>{@link #observe()} accumulates without reporting and is cheap enough to run on every model
 * change on the emulator thread, which keeps the count exact even when a burst of output is
 * longer than the whole scrollback before the UI thread gets to look. {@link #poll()} measures
 * once more, then reports and resets what was accumulated; {@link #drain()} only reports and
 * resets. Every method that reads the buffer takes the {@link LineSource} lock before the
 * tracker's own monitor, the order the emulator thread already holds them in, and
 * {@code drain()} takes only the monitor, so the two can never be taken in the opposite order.
 */
public final class ScrollbackTrimTracker {

    /** The part of a terminal buffer the tracker reads. */
    public interface LineSource {

        /** Number of lines currently in the scrollback history. */
        int historyCount();

        /**
         * The history line at {@code index} ({@code 0 <= index < historyCount()}), compared by
         * identity only.
         */
        Object historyLine(int index);

        /**
         * Number of lines the screen buffer holds. Only its drop to zero matters: the emulator
         * never leaves the screen empty while it writes, but Clear Buffer and a reset do.
         */
        int screenCount();

        /** Whether the alternate screen is active (the primary history is swapped out). */
        boolean alternateScreen();

        /** Excludes concurrent buffer changes while the tracker reads; re-entrant. */
        default void lock() {
        }

        /** Releases {@link #lock()}. */
        default void unlock() {
        }
    }

    /** What happened to the scrollback since the previous poll. */
    public record Trim(Kind kind, int lines) {

        public enum Kind {
            SHIFT,
            CLEARED,
            UNKNOWN,
            SUSPENDED
        }

        private static final Trim NONE = new Trim(Kind.SHIFT, 0);
        private static final Trim CLEARED_TRIM = new Trim(Kind.CLEARED, 0);
        private static final Trim UNKNOWN_TRIM = new Trim(Kind.UNKNOWN, 0);
        private static final Trim SUSPENDED_TRIM = new Trim(Kind.SUSPENDED, 0);

        public Trim {
            Objects.requireNonNull(kind, "kind");
            if (lines < 0) {
                throw new IllegalArgumentException("lines must not be negative: " + lines);
            }
        }

        public static Trim none() {
            return NONE;
        }

        public static Trim shift(int lines) {
            return lines == 0 ? NONE : new Trim(Kind.SHIFT, lines);
        }

        public static Trim cleared() {
            return CLEARED_TRIM;
        }

        public static Trim unknown() {
            return UNKNOWN_TRIM;
        }

        public static Trim suspended() {
            return SUSPENDED_TRIM;
        }
    }

    private final LineSource source;

    // Guarded by this, and only touched while source.lock() is held as well.
    private Object anchorLine;
    private int anchorIndex = -1;
    private boolean screenHadLines;
    // Guarded by this.
    private int pendingShift;
    private boolean pendingCleared;
    private boolean pendingUnknown;

    public ScrollbackTrimTracker(LineSource source) {
        this.source = Objects.requireNonNull(source, "source");
        reset();
    }

    /** A tracker over a vendor terminal buffer, reading it under the buffer's own lock. */
    public static ScrollbackTrimTracker forBuffer(TerminalTextBuffer buffer) {
        return new ScrollbackTrimTracker(new TextBufferLineSource(buffer));
    }

    /**
     * Measures the scrollback and adds the result to what the next {@link #poll()} reports.
     * Safe to call from the emulator thread inside a model listener.
     */
    public void observe() {
        source.lock();
        try {
            synchronized (this) {
                accumulate(measure());
            }
        } finally {
            source.unlock();
        }
    }

    /**
     * Measures the scrollback and returns everything since the previous poll: {@code CLEARED}
     * if the buffer was cleared at any point (later shifts only moved lines that are gone),
     * otherwise the summed shift, otherwise {@code UNKNOWN} if the anchor was lost, otherwise
     * {@code SUSPENDED} while the alternate screen is active.
     */
    public Trim poll() {
        source.lock();
        try {
            synchronized (this) {
                Trim current = measure();
                accumulate(current);
                return takePending(current.kind() == Trim.Kind.SUSPENDED);
            }
        } finally {
            source.unlock();
        }
    }

    /**
     * Returns and resets what {@link #observe()} accumulated, without reading the buffer or
     * taking its lock. Meant for a caller that knows {@code observe()} already ran for the latest
     * change (the deferred UI half of a model listener), where measuring again would only make
     * the UI thread contend with the emulator for the buffer lock. Reports {@code SHIFT 0}
     * rather than {@code SUSPENDED} when nothing is pending, because it does not look.
     */
    public synchronized Trim drain() {
        return takePending(false);
    }

    /**
     * Forgets anything accumulated and re-anchors on the current newest history line, for when
     * the marks were replaced wholesale (a restored project) and older trims no longer apply.
     */
    public void reset() {
        source.lock();
        try {
            synchronized (this) {
                clearPending();
                anchorLine = null;
                anchorIndex = -1;
                screenHadLines = false;
                if (!source.alternateScreen()) {
                    anchor(Math.max(0, source.historyCount()));
                    screenHadLines = source.screenCount() > 0;
                }
            }
        } finally {
            source.unlock();
        }
    }

    private Trim measure() {
        if (source.alternateScreen()) {
            return Trim.suspended();
        }
        int count = Math.max(0, source.historyCount());
        boolean screenHasLines = source.screenCount() > 0;
        boolean screenWiped = screenHadLines && !screenHasLines;
        screenHadLines = screenHasLines;
        if (count == 0) {
            boolean hadHistory = anchorLine != null;
            anchorLine = null;
            anchorIndex = -1;
            // With no history the marks sit on screen rows, so an emptied screen took them too.
            return hadHistory || screenWiped ? Trim.cleared() : Trim.none();
        }
        if (anchorLine == null) {
            anchor(count);
            return Trim.none();
        }
        // Lines only ever leave from the top, so the anchor can only have moved up: search from
        // its last index downwards. A trim of n lines costs n + 1 identity checks.
        for (int index = Math.min(anchorIndex, count - 1); index >= 0; index--) {
            if (source.historyLine(index) == anchorLine) {
                int trimmed = anchorIndex - index;
                anchor(count);
                return Trim.shift(trimmed);
            }
        }
        anchor(count);
        return Trim.unknown();
    }

    private void anchor(int count) {
        if (count <= 0) {
            anchorLine = null;
            anchorIndex = -1;
            return;
        }
        anchorIndex = count - 1;
        anchorLine = source.historyLine(anchorIndex);
    }

    private void accumulate(Trim trim) {
        switch (trim.kind()) {
            case CLEARED -> {
                pendingCleared = true;
                pendingShift = 0;
            }
            case SHIFT -> {
                if (!pendingCleared) {
                    pendingShift += trim.lines();
                }
            }
            case UNKNOWN -> pendingUnknown = true;
            case SUSPENDED -> {
                // The primary history is swapped out; nothing moved that the marks refer to.
            }
        }
    }

    private Trim takePending(boolean suspended) {
        Trim result;
        if (pendingCleared) {
            result = Trim.cleared();
        } else if (pendingShift > 0) {
            result = Trim.shift(pendingShift);
        } else if (pendingUnknown) {
            result = Trim.unknown();
        } else if (suspended) {
            result = Trim.suspended();
        } else {
            result = Trim.none();
        }
        clearPending();
        return result;
    }

    private void clearPending() {
        pendingShift = 0;
        pendingCleared = false;
        pendingUnknown = false;
    }

    /** Reads the vendor buffer's history; {@code getLine} is only called for existing rows. */
    private record TextBufferLineSource(TerminalTextBuffer buffer) implements LineSource {

        TextBufferLineSource {
            Objects.requireNonNull(buffer, "buffer");
        }

        @Override
        public int historyCount() {
            return buffer.getHistoryLinesCount();
        }

        @Override
        public Object historyLine(int index) {
            return buffer.getHistoryBuffer().getLine(index);
        }

        @Override
        public int screenCount() {
            return buffer.getScreenLinesCount();
        }

        @Override
        public boolean alternateScreen() {
            return buffer.isUsingAlternateBuffer();
        }

        @Override
        public void lock() {
            buffer.lock();
        }

        @Override
        public void unlock() {
            buffer.unlock();
        }
    }
}
