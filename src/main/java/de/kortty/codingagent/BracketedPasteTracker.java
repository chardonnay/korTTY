package de.kortty.codingagent;

import de.kortty.core.ObservableTtyConnector;

/**
 * Tracks DECSET/DECRST 2004 (bracketed paste) in the output stream of one pane. Registered as a
 * {@link ObservableTtyConnector.DataListener} on the pane's connector, it parses every chunk on the
 * terminal reader thread together with a short carry-over tail (a sequence may be split across
 * chunks) and flips a volatile flag read from the UI thread. {@link #reset()} on rebind.
 */
public final class BracketedPasteTracker implements ObservableTtyConnector.DataListener {

    public static final String ENABLE = "\u001b[?2004h";
    public static final String DISABLE = "\u001b[?2004l";

    /** Longest carry-over kept between chunks (a DECSET with a few parameters fits). */
    static final int MAX_TAIL = 15;

    private static final char ESC = '\u001b';
    private static final String PRIVATE_MODE_PREFIX = "\u001b[?";
    private static final String BRACKETED_PASTE_MODE = "2004";

    private volatile boolean enabled;
    private String tail = "";

    public BracketedPasteTracker() {
    }

    @Override
    public void onData(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        synchronized (this) {
            String combined = tail.isEmpty() ? data : tail + data;
            enabled = scan(combined, enabled);
            tail = combined.length() <= MAX_TAIL ? combined : combined.substring(combined.length() - MAX_TAIL);
        }
    }

    /** True while the application in the pane has bracketed paste enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Forgets the mode and the carry-over (the pane was rebound to a fresh connector). */
    public void reset() {
        synchronized (this) {
            enabled = false;
            tail = "";
        }
    }

    /** The current carry-over, for tests. */
    synchronized String carry() {
        return tail;
    }

    /**
     * Pure scan: returns the mode after applying every complete {@code ESC [ ? params h|l} whose
     * parameter list contains 2004 in {@code data}, starting from {@code current}. Incomplete
     * sequences at the end are ignored (the tracker re-scans them with the next chunk).
     */
    public static boolean scan(String data, boolean current) {
        boolean result = current;
        if (data == null || data.isEmpty()) {
            return result;
        }
        int from = 0;
        while (true) {
            int start = data.indexOf(PRIVATE_MODE_PREFIX, from);
            if (start < 0) {
                return result;
            }
            int index = start + PRIVATE_MODE_PREFIX.length();
            int paramsStart = index;
            while (index < data.length()) {
                char c = data.charAt(index);
                if ((c >= '0' && c <= '9') || c == ';') {
                    index++;
                } else {
                    break;
                }
            }
            if (index >= data.length()) {
                return result;
            }
            char terminator = data.charAt(index);
            if ((terminator == 'h' || terminator == 'l') && mentionsBracketedPaste(data, paramsStart, index)) {
                result = terminator == 'h';
            }
            from = terminator == ESC ? index : index + 1;
        }
    }

    private static boolean mentionsBracketedPaste(String data, int from, int to) {
        int cursor = from;
        while (cursor < to) {
            int next = data.indexOf(';', cursor);
            if (next < 0 || next > to) {
                next = to;
            }
            if (data.regionMatches(cursor, BRACKETED_PASTE_MODE, 0, BRACKETED_PASTE_MODE.length())
                    && next - cursor == BRACKETED_PASTE_MODE.length()) {
                return true;
            }
            cursor = next + 1;
        }
        return false;
    }
}
