package de.kortty.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What "Summarize Recent Output" sends from a pane when nothing is selected: the output of the
 * newest finished command when shell integration marked it (the range Copy Last Output copies),
 * otherwise the last {@value #MAX_LINES} lines of the pane. Either way the text is cleaned of
 * terminal escape sequences and control characters and capped at {@value #MAX_CHARS} characters,
 * keeping the end, where the newest output stands.
 *
 * <p>The pane is read through a {@link Reader}; the live one reads the buffer on the FX thread
 * under the buffer lock, bounded by the scrollback and by {@value #MAX_LINES} lines for the
 * fallback. Masking and the AI call happen elsewhere, the call off the FX thread. Toolkit-free.
 */
final class TerminalRecentOutputSource {

    /** The most lines the fallback reads from the end of the pane. */
    static final int MAX_LINES = 200;
    /** The most characters sent, from the end of the text. */
    static final int MAX_CHARS = 16_384;

    /** CSI and OSC sequences, then other escapes (charset designations and the like). */
    private static final Pattern CSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern OSC = Pattern.compile("\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?");
    private static final Pattern OTHER_ESCAPE = Pattern.compile("\\u001B[ -/]*[0-~]?");

    /** Where the text came from, for the status bar. */
    enum Origin {
        /** The newest finished command's output, from the shell-integration marks. */
        LAST_COMMAND,
        /** The last lines of the pane. */
        RECENT_LINES
    }

    /**
     * The text to summarize.
     *
     * @param truncated whether the start was cut off to fit {@value #MAX_CHARS} characters
     */
    record RecentOutput(@NotNull String text, @NotNull Origin origin, boolean truncated) {
    }

    /** Reads one pane. FX thread for the live implementation. */
    interface Reader {
        /** The newest finished command's output, or {@code null} without marks or such a command. */
        @Nullable String lastCommandOutput();

        /** The pane's last lines, oldest first, or {@code null} while a full-screen program runs. */
        @Nullable List<String> tail(int maxLines);
    }

    private TerminalRecentOutputSource() {
    }

    /** The recent output of the pane {@code reader} reads, or {@code null} when it has none. */
    static @Nullable RecentOutput read(@NotNull Reader reader) {
        String command = clean(reader.lastCommandOutput());
        if (!command.isBlank()) {
            return capped(command, Origin.LAST_COMMAND);
        }
        List<String> lines = reader.tail(MAX_LINES);
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int from = Math.max(0, lines.size() - MAX_LINES);
        String tail = clean(String.join("\n", lines.subList(from, lines.size())));
        return tail.isBlank() ? null : capped(tail, Origin.RECENT_LINES);
    }

    /**
     * {@code text} without terminal escape sequences and control characters other than line feed
     * and tab, with CR LF and lone CR made line feeds, and without blank lines at either end.
     */
    static @NotNull String clean(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = OTHER_ESCAPE.matcher(OSC.matcher(CSI.matcher(text).replaceAll("")).replaceAll(""))
            .replaceAll("");
        String normalized = stripped.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\n' || c == '\t' || !(Character.isISOControl(c) || isBidiControl(c))) {
                out.append(c);
            }
        }
        return trimBlankLines(out.toString());
    }

    /** The last {@value #MAX_CHARS} characters of {@code text}, starting at a line when it can. */
    static @NotNull RecentOutput capped(@NotNull String text, @NotNull Origin origin) {
        if (text.length() <= MAX_CHARS) {
            return new RecentOutput(text, origin, false);
        }
        int start = text.length() - MAX_CHARS;
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        int lineStart = text.indexOf('\n', start);
        if (lineStart >= 0 && lineStart + 1 < text.length()) {
            start = lineStart + 1;
        }
        return new RecentOutput(text.substring(start), origin, true);
    }

    private static boolean isBidiControl(char c) {
        return (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩')
            || c == '‎' || c == '‏' || c == '؜';
    }

    private static String trimBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        int last = lines.length - 1;
        while (last >= first && lines[last].isBlank()) {
            last--;
        }
        if (first > last) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                out.append('\n');
            }
            out.append(lines[i].stripTrailing());
        }
        return out.toString();
    }
}
