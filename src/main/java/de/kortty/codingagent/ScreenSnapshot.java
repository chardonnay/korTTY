package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable capture of the live terminal screen (visible rows, never scrollback). Rows are
 * right-trimmed (TerminalTextBuffer.getScreenLines() pads every row to the terminal width).
 * oscTitle is null when no OSC 0/2 title was received (TerminalPanel's default "Terminal" maps to null).
 */
public record ScreenSnapshot(List<String> lines, int columns, int rows, String oscTitle, boolean alternateScreen) {

    public static final ScreenSnapshot EMPTY = new ScreenSnapshot(List.of(), 0, 0, null, false);

    private static final String DEFAULT_TITLE = "Terminal";

    public ScreenSnapshot {
        lines = List.copyOf(lines == null ? List.of() : lines);
        oscTitle = (oscTitle == null || oscTitle.isBlank() || DEFAULT_TITLE.equals(oscTitle)) ? null : oscTitle;
    }

    /**
     * Single normalisation point used by TerminalScreenCapture and by test fixtures: drops a trailing
     * empty element produced by a terminating newline and right-trims every row.
     */
    public static ScreenSnapshot ofLines(List<String> rawLines, int columns, String rawWindowTitle, boolean alternateScreen) {
        List<String> rows = new ArrayList<>(rawLines == null ? List.of() : rawLines);
        if (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        rows.replaceAll(String::stripTrailing);
        return new ScreenSnapshot(rows, Math.max(0, columns), rows.size(), rawWindowTitle, alternateScreen);
    }

    /** Splits {@code screenText} on '\n'; columns = longest raw row. */
    public static ScreenSnapshot ofText(String screenText, String rawWindowTitle, boolean alternateScreen) {
        if (screenText == null || screenText.isEmpty()) {
            return new ScreenSnapshot(List.of(), 0, 0, rawWindowTitle, alternateScreen);
        }
        List<String> raw = List.of(screenText.split("\n", -1));
        int width = raw.stream().mapToInt(String::length).max().orElse(0);
        return ofLines(raw, width, rawWindowTitle, alternateScreen);
    }

    public boolean hasOscTitle() {
        return oscTitle != null;
    }

    /** The last {@code count} non-blank rows in screen order; count &lt;= 0 returns every row. */
    public List<String> bottomNonEmptyLines(int count) {
        if (count <= 0) {
            return lines;
        }
        List<String> result = new ArrayList<>(count);
        for (int i = lines.size() - 1; i >= 0 && result.size() < count; i--) {
            if (!lines.get(i).isBlank()) {
                result.add(lines.get(i));
            }
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public static String joined(List<String> lines) {
        return String.join("\n", lines);
    }
}
