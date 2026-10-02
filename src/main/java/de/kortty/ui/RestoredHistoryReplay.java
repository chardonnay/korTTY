package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.util.CharUtils;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the screen text a project saved for a tab in the new session's terminal — locally, in the
 * emulator, and nowhere else.
 *
 * <p>This used to be done by typing a heredoc into the remote shell, which made the shell expand
 * {@code $VAR}, {@code $(…)} and backticks from previously displayed output — or run them. The
 * saved text is old output, not input, so it never goes near a {@code TtyConnector}: this class
 * takes no connector, and {@link #replay} writes through {@link Terminal#writeUnwrappedString},
 * which puts characters into the text buffer without parsing escape sequences.
 *
 * <p>{@link #sanitize} additionally removes every control character, so even a parser could not
 * be steered by the saved text, and the block is rendered dimmed between two markers so it cannot
 * be mistaken for live output.
 */
final class RestoredHistoryReplay {

    /** The newest rows kept; a saved screen is far smaller, this only bounds a crafted file. */
    static final int MAX_LINES = 5000;

    private static final int TAB_STOP = 8;

    private static final String MARKER_PREFIX = "── ";

    private static final String MARKER_SUFFIX = " ──";

    private RestoredHistoryReplay() {
    }

    /**
     * Turns saved screen text into the rows {@link #replay} writes.
     *
     * <p>Line breaks are normalised to LF and TAB expands to spaces on 8-column stops. Every other
     * C0/C1 control character, DEL and ESC included, is dropped, as is the emulator's placeholder
     * for the second cell of a double-width character (the emulator adds it again on write). Row
     * padding is stripped, runs of blank rows collapse to one, blank rows at either end are dropped,
     * and only the newest {@link #MAX_LINES} rows are kept.
     *
     * @param raw the saved text, may be {@code null}
     * @return the rows to show, oldest first; empty when nothing printable is left
     */
    static List<String> sanitize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String rawLine : normalized.split("\n", -1)) {
            String line = cleanLine(rawLine);
            boolean blank = line.isEmpty();
            if (blank && (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty())) {
                continue;
            }
            lines.add(line);
        }
        // Leading blanks were never added; a run of blanks at the end collapsed to one.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() > MAX_LINES) {
            lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
            if (lines.get(0).isEmpty()) {
                lines.remove(0);
            }
        }
        return List.copyOf(lines);
    }

    private static String cleanLine(String rawLine) {
        StringBuilder line = new StringBuilder(rawLine.length());
        rawLine.codePoints().forEach(codePoint -> {
            if (codePoint == '\t') {
                int spaces = TAB_STOP - (line.length() % TAB_STOP);
                line.append(" ".repeat(spaces));
            } else if (codePoint != CharUtils.DWC && !Character.isISOControl(codePoint)) {
                line.appendCodePoint(codePoint);
            }
        });
        return line.toString().stripTrailing();
    }

    /**
     * Writes {@code lines} into {@code terminal} as one dimmed block framed by {@code header} and
     * {@code footer} (dimmed and italic), starting on a fresh row.
     *
     * <p>Call it before the emulator starts reading the connection, on the thread that owns the
     * terminal — then the block sits between the local "Connecting…" message and the first live
     * byte. The style that was current before the call is restored even if a write fails, so the
     * session's own output never inherits the dim attribute.
     *
     * @param terminal the emulator of the pane to write into
     * @param lines rows from {@link #sanitize}; nothing is written when empty
     * @param header the first marker row, {@code null} for none
     * @param footer the last marker row, {@code null} for none
     */
    static void replay(Terminal terminal, List<String> lines, @Nullable String header, @Nullable String footer) {
        if (terminal == null || lines == null || lines.isEmpty()) {
            return;
        }
        StyleState styleState = terminal.getStyleState();
        TextStyle previous = styleState.getCurrent();
        TextStyle dim = previous.toBuilder().setOption(TextStyle.Option.DIM, true).build();
        TextStyle marker = dim.toBuilder().setOption(TextStyle.Option.ITALIC, true).build();
        try {
            if (!atLineStart(terminal)) {
                nextRow(terminal);
            }
            writeMarker(terminal, marker, header);
            terminal.characterAttributes(dim);
            for (String line : lines) {
                writeRow(terminal, line);
            }
            writeMarker(terminal, marker, footer);
        } finally {
            terminal.characterAttributes(previous);
        }
    }

    /**
     * The saved-at time as the header shows it: a short localized date and time.
     *
     * @param savedAt when the project was saved
     * @param locale the UI locale, {@code null} for the JVM default
     */
    static String formatSavedAt(LocalDateTime savedAt, @Nullable Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale != null ? locale : Locale.getDefault())
            .format(savedAt);
    }

    private static void writeMarker(Terminal terminal, TextStyle style, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        terminal.characterAttributes(style);
        writeRow(terminal, MARKER_PREFIX + cleanLine(text) + MARKER_SUFFIX);
    }

    /**
     * Writes one row and moves to the start of the next. A row that exactly fills the width has
     * already been wrapped onto the next row by the emulator; a second line feed would leave a
     * blank row in the middle of what was one long line on the saved screen.
     */
    private static void writeRow(Terminal terminal, String text) {
        terminal.writeUnwrappedString(text);
        boolean wrappedOntoNextRow = !text.isEmpty()
            && atLineStart(terminal)
            && terminal instanceof SithTerminal sithTerminal
            && sithTerminal.isAutoWrap();
        if (!wrappedOntoNextRow) {
            nextRow(terminal);
        }
    }

    private static boolean atLineStart(Terminal terminal) {
        return terminal.distanceToLineEnd() >= terminal.getTerminalWidth();
    }

    private static void nextRow(Terminal terminal) {
        terminal.carriageReturn();
        terminal.newLine();
    }
}
