package de.kortty.core;

import java.util.function.Consumer;

/**
 * Turns the raw decoded output stream of a terminal connection into clean, journal-ready lines:
 * strips ANSI/OSC/DCS escape sequences with a state machine that survives chunk boundaries,
 * emulates carriage-return overwrites (progress bars collapse to their final state), and flushes
 * a pending prompt without a trailing newline as a {@code partial} line after an idle period.
 *
 * <p>All entry points are synchronized; output arrives on the connector reader thread while the
 * idle flush ticks on a scheduler thread.</p>
 */
public final class SessionJournalAnsiProcessor {

    /** A line handed to the sink; {@code partial} lines keep accumulating in the buffer. */
    public record EmittedLine(String text, boolean partial) {
    }

    private enum EscState { NONE, ESC, CSI, OSC, OSC_ESC, DCS, DCS_ESC, SS3, CHARSET }

    /** Force-emit guard against binary floods (e.g. cat-ing a binary file). */
    static final int MAX_LINE_CHARS = 16 * 1024;

    private final Consumer<EmittedLine> sink;
    private final StringBuilder line = new StringBuilder(256);
    private EscState escState = EscState.NONE;
    private boolean overwritePending;
    private boolean lastEmittedBlank;
    private String lastPartialEmitted;
    private long lastAppendNanos;

    public SessionJournalAnsiProcessor(Consumer<EmittedLine> sink) {
        this.sink = sink;
    }

    /** Processes a decoded output chunk (may split escape sequences and UTF-8 lines arbitrarily). */
    public synchronized void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunk.length(); i++) {
            process(chunk.charAt(i));
        }
        lastAppendNanos = System.nanoTime();
    }

    /** The currently unemitted line buffer — what a pending prompt looks like. */
    public synchronized String pendingLine() {
        return line.toString();
    }

    /**
     * Emits the pending buffer as a {@code partial} line when no output arrived for
     * {@code idleMillis} — this is how prompts without a trailing newline reach the log.
     * The buffer is kept; if the line completes later, the full line is emitted again.
     */
    public synchronized void flushIdle(long idleMillis) {
        if (line.length() == 0) {
            return;
        }
        long idleNanos = (System.nanoTime() - lastAppendNanos);
        if (idleNanos < idleMillis * 1_000_000L) {
            return;
        }
        String text = line.toString().stripTrailing();
        if (text.isEmpty() || text.equals(lastPartialEmitted)) {
            return;
        }
        lastPartialEmitted = text;
        sink.accept(new EmittedLine(text, true));
    }

    /** Final flush on session close: whatever is pending becomes a normal line. */
    public synchronized void flushRemaining() {
        if (line.length() > 0) {
            emitLine();
        }
    }

    private void process(char c) {
        switch (escState) {
            case ESC -> {
                switch (c) {
                    case '[' -> escState = EscState.CSI;
                    case ']' -> escState = EscState.OSC;
                    case 'P', 'X', '^', '_' -> escState = EscState.DCS;
                    case 'O' -> escState = EscState.SS3;
                    case '(', ')', '*', '+' -> escState = EscState.CHARSET;
                    default -> escState = EscState.NONE; // single-character escape consumed
                }
                return;
            }
            case CSI -> {
                if (c >= 0x40 && c <= 0x7E) {
                    escState = EscState.NONE;
                }
                return;
            }
            case OSC -> {
                if (c == 0x07) {
                    escState = EscState.NONE;
                } else if (c == 0x1B) {
                    escState = EscState.OSC_ESC;
                }
                return;
            }
            case OSC_ESC -> {
                escState = c == '\\' ? EscState.NONE : EscState.OSC;
                return;
            }
            case DCS -> {
                if (c == 0x1B) {
                    escState = EscState.DCS_ESC;
                }
                return;
            }
            case DCS_ESC -> {
                escState = c == '\\' ? EscState.NONE : EscState.DCS;
                return;
            }
            case SS3, CHARSET -> {
                escState = EscState.NONE;
                return;
            }
            case NONE -> {
                // fall through to normal character handling below
            }
        }

        if (c == 0x1B) {
            escState = EscState.ESC;
            return;
        }
        if (c == '\n') {
            overwritePending = false;
            emitLine();
            return;
        }
        if (c == '\r') {
            // A lone CR starts an overwrite (progress bars); CR directly followed by LF is a
            // normal line break handled above.
            overwritePending = true;
            return;
        }
        if (c == '\b') {
            if (line.length() > 0) {
                line.deleteCharAt(line.length() - 1);
            }
            return;
        }
        if (c == 0x7F || (c < 0x20 && c != '\t')) {
            return;
        }
        if (overwritePending) {
            line.setLength(0);
            overwritePending = false;
        }
        line.append(c);
        if (line.length() >= MAX_LINE_CHARS) {
            emitLine();
        }
    }

    private void emitLine() {
        String text = line.toString().stripTrailing();
        line.setLength(0);
        lastPartialEmitted = null;
        boolean blank = text.isEmpty();
        if (blank && lastEmittedBlank) {
            return; // collapse runs of blank lines to a single one
        }
        lastEmittedBlank = blank;
        sink.accept(new EmittedLine(text, false));
    }
}
