package de.kortty.ui;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Re-keys command timestamps when the scrollback drops lines from its top.
 *
 * <p>A timestamp is keyed by absolute line (history line count plus cursor row). When the
 * history is full, trimming {@code n} lines moves every remaining line {@code n} rows up, so each
 * key has to move by the same amount; keys that would become negative belonged to lines that no
 * longer exist and are dropped.
 */
public final class TimestampHistory {

    private TimestampHistory() {
    }

    /**
     * Returns a new map with every key reduced by {@code lines}, dropping keys below
     * {@code lines}. The source map is not modified; {@code lines <= 0} returns a plain copy.
     */
    public static <V> TreeMap<Integer, V> shift(NavigableMap<Integer, V> history, int lines) {
        TreeMap<Integer, V> shifted = new TreeMap<>();
        if (history == null || history.isEmpty()) {
            return shifted;
        }
        if (lines <= 0) {
            shifted.putAll(history);
            return shifted;
        }
        for (Map.Entry<Integer, V> entry : history.tailMap(lines, true).entrySet()) {
            shifted.put(entry.getKey() - lines, entry.getValue());
        }
        return shifted;
    }

    /**
     * Moves one absolute line up by {@code lines}; returns {@code -1} when the line was trimmed
     * (or was already unset).
     */
    public static int shiftLine(int absoluteLine, int lines) {
        if (absoluteLine < 0) {
            return -1;
        }
        if (lines <= 0) {
            return absoluteLine;
        }
        int shifted = absoluteLine - lines;
        return shifted >= 0 ? shifted : -1;
    }
}
