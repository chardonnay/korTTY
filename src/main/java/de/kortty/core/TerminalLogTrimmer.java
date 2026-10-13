package de.kortty.core;

import de.kortty.model.TerminalLogConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a korTTY terminal log down to the entries inside a time range, one line at a time.
 *
 * <p>The output is a complete document in the same format. The header and footer are regenerated
 * from the format and the connection name rather than copied, so a source that was cut short before
 * its footer was written still yields valid XML or JSON. Entries are copied as they were written,
 * so what the user reads in the export is what the logger recorded.</p>
 *
 * <p>An entry starts at a line matching the format's start pattern. What follows it belongs to it,
 * up to the next start line or the footer. Plain text has no closing marker, so every such line is
 * part of the entry: escaping does not stop a carriage return or line feed from reaching the file,
 * and redaction can insert line breaks. XML closes an entry at the line ending in {@code </entry>},
 * which is unambiguous because the text inside is escaped. JSON entries are always one line, since
 * escaping turns line breaks into {@code \n}; anything else found between entries is not content and
 * is dropped rather than glued onto a neighbour.</p>
 *
 * <p>An entry is kept or dropped as a whole, by the timestamp on its start line. An entry the source
 * cut short, with no closing {@code </entry>} or a JSON line that does not end in its closing brace,
 * is dropped too: copying a fragment would make the output invalid.</p>
 */
public final class TerminalLogTrimmer {

    /** The stamp as {@link TerminalLogger} writes it; {@link TerminalLogger#TIMESTAMP_FORMAT} reads it back. */
    private static final String STAMP = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})";
    private static final Pattern PLAIN_START = Pattern.compile("^\\[" + STAMP + "\\](?: |$)");
    private static final Pattern XML_START = Pattern.compile("^\\s*<entry timestamp=\"" + STAMP + "\"(?: [^>]*?)?>");
    private static final Pattern JSON_START = Pattern.compile("^\\s*\\{\"timestamp\": \"" + STAMP + "\", \"line\": ");
    private static final Pattern XML_CONNECTION = Pattern.compile("<terminal-log connection=\"([^\"]*)\"(?: [^>]*?)?>");
    private static final String XML_ENTRY_END = "</entry>";
    private static final String XML_DOCUMENT_END = "</terminal-log>";
    private static final String JSON_CONNECTION = "\"connection\": \"";

    private TerminalLogTrimmer() {
    }

    /** Raised when the caller cancels mid-trim; an IOException so the writer's cleanup path runs. */
    static final class CancelledException extends IOException {

        private static final long serialVersionUID = 1L;

        CancelledException() {
            super("Trim cancelled");
        }
    }

    /**
     * Copies the entries of {@code in} that fall inside {@code range} to {@code out}.
     *
     * <p>Streams line by line, so memory use is bounded by the longest single entry rather than the
     * file. The {@code connectionName} is only a fallback for sources that have no header of their own;
     * a header in the source always wins, so the export keeps the name the logger recorded.</p>
     *
     * @param range     the entries to keep; {@code null} keeps everything
     * @param cancelled polled once per input line; {@code null} means never cancelled
     * @return the number of entries written
     * @throws CancelledException when {@code cancelled} reports true
     */
    public static long trim(BufferedReader in, Writer out, TerminalLogConfig.LogFormat format,
                            String connectionName, LogTimeRange range, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(format, "format");
        return new Trim(in, out, format, connectionName, range,
            cancelled != null ? cancelled : () -> false).run();
    }

    /** The state of one trim run; kept separate so the public method stays a single call. */
    private static final class Trim {

        private final BufferedReader in;
        private final Writer out;
        private final TerminalLogConfig.LogFormat format;
        private final Pattern startPattern;
        private final String fallbackName;
        private final LogTimeRange range;
        private final BooleanSupplier cancelled;

        /** The connection name carried by the source's own header, unescaped; {@code null} when it has none. */
        private String sourceName;
        private boolean headerWritten;
        private long kept;

        /** The entry being collected, or {@code null} between entries. */
        private StringBuilder pending;
        private boolean pendingKeep;
        private boolean pendingComplete;

        Trim(BufferedReader in, Writer out, TerminalLogConfig.LogFormat format, String fallbackName,
             LogTimeRange range, BooleanSupplier cancelled) {
            this.in = in;
            this.out = out;
            this.format = format;
            this.fallbackName = fallbackName;
            this.range = range;
            this.cancelled = cancelled;
            this.startPattern = switch (format) {
                case PLAIN_TEXT -> PLAIN_START;
                case XML -> XML_START;
                case JSON -> JSON_START;
            };
        }

        long run() throws IOException {
            String line;
            while ((line = in.readLine()) != null) {
                if (cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                Matcher entryStart = startPattern.matcher(line);
                if (entryStart.lookingAt()) {
                    beginEntry(entryStart.group(1), line);
                } else if (format == TerminalLogConfig.LogFormat.XML && XML_DOCUMENT_END.equals(line.strip())) {
                    flushPending();
                } else if (format == TerminalLogConfig.LogFormat.JSON && isJsonBracket(line)) {
                    flushPending();
                } else {
                    continuation(line);
                }
            }
            flushPending();
            ensureHeader();
            writeFooter();
            out.flush();
            return kept;
        }

        private void beginEntry(String timestamp, String line) throws IOException {
            flushPending();
            ensureHeader();
            pending = new StringBuilder(line);
            pendingKeep = keep(timestamp);
            pendingComplete = switch (format) {
                case PLAIN_TEXT -> true;
                case XML -> line.stripTrailing().endsWith(XML_ENTRY_END);
                case JSON -> jsonComplete(withoutTrailingComma(line));
            };
        }

        private void continuation(String line) {
            if (pending == null) {
                readPreamble(line);
                return;
            }
            switch (format) {
                case PLAIN_TEXT -> pending.append('\n').append(line);
                case XML -> {
                    // Once the entry has closed, anything further is outside it and dropped.
                    if (!pendingComplete) {
                        pending.append('\n').append(line);
                        pendingComplete = line.stripTrailing().endsWith(XML_ENTRY_END);
                    }
                }
                // A JSON entry is a single line, so a stray line is not part of it.
                case JSON -> {
                }
            }
        }

        private void flushPending() throws IOException {
            if (pending == null) {
                return;
            }
            if (pendingKeep && pendingComplete) {
                String text = pending.toString();
                if (format == TerminalLogConfig.LogFormat.JSON) {
                    // Entries are separated by ",\n" and the last one carries no comma, the same
                    // shape TerminalLogger writes; the footer supplies the closing newline.
                    if (kept > 0) {
                        out.write(",\n");
                    }
                    out.write(withoutTrailingComma(text));
                } else {
                    out.write(text);
                    out.write('\n');
                }
                kept++;
            }
            pending = null;
        }

        private void ensureHeader() throws IOException {
            if (headerWritten) {
                return;
            }
            headerWritten = true;
            String name = sourceName != null ? sourceName : fallbackName;
            String shown = name != null ? name : "";
            switch (format) {
                case XML -> out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<terminal-log connection=\""
                    + escapeXml(shown) + "\">\n");
                case JSON -> out.write("{\n  \"connection\": \"" + escapeJson(shown) + "\",\n  \"entries\": [\n");
                case PLAIN_TEXT -> {
                }
            }
        }

        private void writeFooter() throws IOException {
            switch (format) {
                case XML -> out.write(XML_DOCUMENT_END + "\n");
                // Without entries the bracket follows the opening line directly, as TerminalLogger writes it.
                case JSON -> out.write(kept > 0 ? "\n  ]\n}\n" : "  ]\n}\n");
                case PLAIN_TEXT -> {
                }
            }
        }

        private boolean keep(String timestamp) {
            if (range == null) {
                return true;
            }
            try {
                return range.contains(LocalDateTime.parse(timestamp, TerminalLogger.TIMESTAMP_FORMAT));
            } catch (DateTimeParseException e) {
                return false;
            }
        }

        /** Reads the connection name from the lines before the first entry, the only place a header sits. */
        private void readPreamble(String line) {
            if (headerWritten || sourceName != null) {
                return;
            }
            switch (format) {
                case XML -> {
                    Matcher header = XML_CONNECTION.matcher(line);
                    if (header.find()) {
                        sourceName = xmlUnescape(header.group(1));
                    }
                }
                case JSON -> {
                    String value = jsonConnectionValue(line);
                    if (value != null) {
                        sourceName = value;
                    }
                }
                case PLAIN_TEXT -> {
                }
            }
        }
    }

    private static boolean isJsonBracket(String line) {
        String text = line.strip();
        return text.equals("]") || text.equals("}");
    }

    /**
     * Whether a JSON entry line is whole. The closing quote must not be escaped, which is decided by
     * the backslashes in front of it: an odd run means the quote is part of the text, and the line was
     * cut inside the value.
     */
    private static boolean jsonComplete(String entry) {
        if (!entry.endsWith("\"}")) {
            return false;
        }
        int backslashes = 0;
        for (int i = entry.length() - 3; i >= 0 && entry.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }

    private static String withoutTrailingComma(String entry) {
        String trimmed = entry.stripTrailing();
        return trimmed.endsWith(",") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** The connection value on a JSON header line such as {@code   "connection": "web01",}, or {@code null}. */
    private static String jsonConnectionValue(String line) {
        String text = line.strip();
        if (!text.startsWith(JSON_CONNECTION)) {
            return null;
        }
        int end;
        if (text.endsWith("\",")) {
            end = text.length() - 2;
        } else if (text.endsWith("\"")) {
            end = text.length() - 1;
        } else {
            return null;
        }
        if (end < JSON_CONNECTION.length()) {
            return null;
        }
        return jsonUnescape(text.substring(JSON_CONNECTION.length(), end));
    }

    /** Reverses {@link #escapeXml}; a single pass, so {@code &amp;lt;} comes back as {@code &lt;}. */
    private static String xmlUnescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                int semicolon = text.indexOf(';', i);
                String replacement = semicolon > i ? xmlEntity(text.substring(i + 1, semicolon)) : null;
                if (replacement != null) {
                    out.append(replacement);
                    i = semicolon;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String xmlEntity(String name) {
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> null;
        };
    }

    /** Reverses {@link #escapeJson} for the escapes that writer produces, plus the other standard ones. */
    private static String jsonUnescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(++i);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append('\\').append(escaped);
            }
        }
        return out.toString();
    }

    /** The logger's own escaping, so a regenerated header matches what the logger would have written. */
    private static String escapeXml(String text) {
        return TerminalLogger.escapeXml(text);
    }

    private static String escapeJson(String text) {
        return TerminalLogger.escapeJson(text);
    }
}
