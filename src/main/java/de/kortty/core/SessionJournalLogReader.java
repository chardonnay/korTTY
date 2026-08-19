package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import de.kortty.model.SessionJournalLogFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads session journal capture logs of any supported format, transparently handling gzipped
 * parts and torn tails: the append-only writer flushes one entry per line and writes the footer
 * only on clean close, so this reader repairs a live or crashed file by dropping an incomplete
 * trailing line and supplying the missing document end.
 */
public final class SessionJournalLogReader {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalLogReader.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_XML_REPAIR_ATTEMPTS = 8;
    private static final int MAX_PART_PROBE = 1000;

    public static final String BASE_FILE_NAME = "session-log";

    private SessionJournalLogReader() {
    }

    /** Plain file name of a log part before compression, e.g. {@code session-log-2.xml}. */
    public static String partFileName(int part, SessionJournalLogFormat format) {
        String ext = format.getExtension();
        return part <= 1 ? BASE_FILE_NAME + "." + ext : BASE_FILE_NAME + "-" + part + "." + ext;
    }

    /**
     * Finds a part file in any format, compressed or not; null when the part does not exist.
     * Probes plain, then {@code .zst}, then legacy {@code .gz} — a journal that rotated across
     * the compression switch legitimately mixes suffixes between parts.
     */
    public static Path findPartFile(Path journalDir, int part) {
        for (SessionJournalLogFormat format : SessionJournalLogFormat.values()) {
            String plainName = partFileName(part, format);
            Path plain = journalDir.resolve(plainName);
            if (Files.isRegularFile(plain)) {
                return plain;
            }
            Path zst = journalDir.resolve(plainName + SessionJournalLogCompressor.ZSTD_SUFFIX);
            if (Files.isRegularFile(zst)) {
                return zst;
            }
            Path gz = journalDir.resolve(plainName + SessionJournalLogCompressor.GZIP_SUFFIX);
            if (Files.isRegularFile(gz)) {
                return gz;
            }
        }
        return null;
    }

    /** Number of contiguous log parts present in the journal directory. */
    public static int countParts(Path journalDir) {
        int count = 0;
        for (int part = 1; part <= MAX_PART_PROBE; part++) {
            if (findPartFile(journalDir, part) == null) {
                break;
            }
            count = part;
        }
        return count;
    }

    /** Parses one part file (recovery semantics; never throws on torn content). */
    public static List<SessionJournalLogEntry> readPart(Path partFile) throws IOException {
        String plainName = SessionJournalLogCompressor.stripCompressionSuffix(
            partFile.getFileName().toString());
        SessionJournalLogFormat format = formatFromFileName(plainName);
        if (format == null) {
            return List.of();
        }
        String content = readContent(partFile);
        return switch (format) {
            case XML -> parseXml(content, partFile);
            case JSON -> parseJsonLines(content, false);
            case YAML -> parseJsonLines(content, true);
        };
    }

    /** All entries with {@code seq > afterSeq}, ascending; reads only the parts it needs. */
    public static List<SessionJournalLogEntry> readAfter(Path journalDir, long afterSeq) throws IOException {
        int parts = countParts(journalDir);
        List<SessionJournalLogEntry> collected = new ArrayList<>();
        for (int part = parts; part >= 1; part--) {
            Path file = findPartFile(journalDir, part);
            if (file == null) {
                break;
            }
            List<SessionJournalLogEntry> partEntries = readPart(file);
            collected.addAll(0, partEntries);
            if (!partEntries.isEmpty() && partEntries.get(0).seq() <= afterSeq) {
                break;
            }
        }
        return collected.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    /** All entries with {@code fromSeq <= seq <= toSeq}, ascending across parts. */
    public static List<SessionJournalLogEntry> readRange(Path journalDir, long fromSeq, long toSeq) throws IOException {
        int parts = countParts(journalDir);
        List<SessionJournalLogEntry> result = new ArrayList<>();
        for (int part = 1; part <= parts; part++) {
            Path file = findPartFile(journalDir, part);
            if (file == null) {
                break;
            }
            List<SessionJournalLogEntry> partEntries = readPart(file);
            if (partEntries.isEmpty()) {
                continue;
            }
            if (partEntries.get(partEntries.size() - 1).seq() < fromSeq) {
                continue;
            }
            for (SessionJournalLogEntry entry : partEntries) {
                if (entry.seq() > toSeq) {
                    return result;
                }
                if (entry.seq() >= fromSeq) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /**
     * The newest {@code maxOutput} output/seed lines and {@code maxInput} input lines. Idle-flushed
     * {@code partial} lines are noise once the full line followed, so they are skipped — except the
     * very last output entry, which is typically the still-pending shell prompt.
     */
    public static SessionJournalLogTail readTail(Path journalDir, int maxOutput, int maxInput) throws IOException {
        int parts = countParts(journalDir);
        List<SessionJournalLogEntry> collected = new ArrayList<>();
        int outSeen = 0;
        int inSeen = 0;
        for (int part = parts; part >= 1 && (outSeen < maxOutput || inSeen < maxInput); part--) {
            Path file = findPartFile(journalDir, part);
            if (file == null) {
                break;
            }
            List<SessionJournalLogEntry> partEntries = readPart(file);
            collected.addAll(0, partEntries);
            for (SessionJournalLogEntry entry : partEntries) {
                if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                    inSeen++;
                } else if (isOutputKind(entry.kind())) {
                    outSeen++;
                }
            }
        }
        long lastOutputSeq = collected.stream()
            .filter(e -> isOutputKind(e.kind()))
            .mapToLong(SessionJournalLogEntry::seq)
            .max()
            .orElse(-1);
        List<SessionJournalLogEntry> output = new ArrayList<>();
        List<SessionJournalLogEntry> input = new ArrayList<>();
        for (SessionJournalLogEntry entry : collected) {
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                input.add(entry);
            } else if (isOutputKind(entry.kind())) {
                if (!entry.partial() || entry.seq() == lastOutputSeq) {
                    output.add(entry);
                }
            }
        }
        if (output.size() > maxOutput) {
            output = new ArrayList<>(output.subList(output.size() - maxOutput, output.size()));
        }
        if (input.size() > maxInput) {
            input = new ArrayList<>(input.subList(input.size() - maxInput, input.size()));
        }
        long firstSeq = Long.MAX_VALUE;
        long lastSeq = 0;
        for (List<SessionJournalLogEntry> list : List.of(output, input)) {
            for (SessionJournalLogEntry entry : list) {
                firstSeq = Math.min(firstSeq, entry.seq());
                lastSeq = Math.max(lastSeq, entry.seq());
            }
        }
        if (firstSeq == Long.MAX_VALUE) {
            firstSeq = 0;
        }
        return new SessionJournalLogTail(output, input, firstSeq, lastSeq);
    }

    private static boolean isOutputKind(SessionJournalLogEntry.Kind kind) {
        return kind == SessionJournalLogEntry.Kind.OUT || kind == SessionJournalLogEntry.Kind.SEED;
    }

    /** True when the part file is compressed (a closed part), by either codec. */
    public static boolean isCompressed(Path partFile) {
        return SessionJournalLogCompressor.isCompressedName(partFile.getFileName().toString());
    }

    /** The on-disk format of a part file, derived from its extension; null when unrecognized. */
    public static SessionJournalLogFormat formatOf(Path partFile) {
        return formatFromFileName(
            SessionJournalLogCompressor.stripCompressionSuffix(partFile.getFileName().toString()));
    }

    /** Raw file content, transparently decompressing a closed part. */
    public static String readRawContent(Path partFile) throws IOException {
        return readContent(partFile);
    }

    /**
     * The header block of a part file, verbatim and including its trailing newline.
     *
     * <p>Needed when a part is rewritten (the filtered HTML bundle): the header carries the
     * {@code tabSessionId}, which lives nowhere else — not in {@link SessionJournalMeta} — so it
     * can only be preserved by copying the original bytes rather than regenerating them.</p>
     *
     * <p>Returns an empty string when the format cannot be determined or the file is shorter than
     * its own header, so a torn part degrades instead of throwing.</p>
     */
    public static String readHeader(Path partFile) throws IOException {
        SessionJournalLogFormat format = formatOf(partFile);
        if (format == null) {
            return "";
        }
        return headerOf(readRawContent(partFile), format);
    }

    /** Testable variant of {@link #readHeader(Path)} operating on already-read content. */
    static String headerOf(String content, SessionJournalLogFormat format) {
        if (content == null || content.isEmpty() || format == null) {
            return "";
        }
        // XML: <?xml?>, <session-log>, <meta/>. JSONL: one meta object. YAML: four scalars plus
        // the "entries:" key that opens the block sequence.
        int headerLines = switch (format) {
            case XML -> 3;
            case JSON -> 1;
            case YAML -> 5;
        };
        int index = 0;
        for (int line = 0; line < headerLines; line++) {
            int newline = content.indexOf('\n', index);
            if (newline < 0) {
                return content.substring(0, index);
            }
            index = newline + 1;
        }
        return content.substring(0, index);
    }

    private static SessionJournalLogFormat formatFromFileName(String plainName) {
        int dot = plainName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return SessionJournalLogFormat.fromKey(plainName.substring(dot + 1));
    }

    private static String readContent(Path file) throws IOException {
        try (InputStream in = SessionJournalLogCompressor.openInput(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<SessionJournalLogEntry> parseXml(String content, Path source) {
        List<String> lines = new ArrayList<>(content.lines().toList());
        for (int attempt = 0; attempt <= MAX_XML_REPAIR_ATTEMPTS && !lines.isEmpty(); attempt++) {
            StringBuilder candidate = new StringBuilder(content.length() + 32);
            for (String line : lines) {
                candidate.append(line).append('\n');
            }
            if (candidate.indexOf("</session-log>") < 0) {
                candidate.append("</session-log>\n");
            }
            try {
                return parseXmlStrict(candidate.toString());
            } catch (XMLStreamException e) {
                // Torn tail: drop the last line and retry (entry text escapes '<' and '>', so a
                // corrupt line cannot masquerade as the document end).
                lines.remove(lines.size() - 1);
            }
        }
        logger.warn("Session journal log part {} could not be parsed even after repair", source.getFileName());
        return List.of();
    }

    private static List<SessionJournalLogEntry> parseXmlStrict(String document) throws XMLStreamException {
        return parseXmlStrict(document, secureXmlInputFactory());
    }

    private static XMLInputFactory secureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    private static List<SessionJournalLogEntry> parseXmlStrict(String document, XMLInputFactory factory)
            throws XMLStreamException {
        List<SessionJournalLogEntry> entries = new ArrayList<>();
        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(document));
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                SessionJournalLogEntry.Kind kind = SessionJournalLogEntry.Kind.fromKey(reader.getLocalName());
                if (kind == null) {
                    continue;
                }
                long seq = parseLong(reader.getAttributeValue(null, "seq"));
                OffsetDateTime timestamp = parseTimestamp(reader.getAttributeValue(null, "t"));
                boolean redacted = "true".equals(reader.getAttributeValue(null, "redacted"));
                boolean partial = "true".equals(reader.getAttributeValue(null, "partial"));
                String file = reader.getAttributeValue(null, "file");
                int repeat = (int) Math.max(1, parseLong(reader.getAttributeValue(null, "repeat")));
                String text = reader.getElementText();
                if (seq >= 0 && timestamp != null) {
                    entries.add(new SessionJournalLogEntry(
                        seq, timestamp, kind, text, redacted, partial, file, repeat));
                }
            }
        } finally {
            reader.close();
        }
        return entries;
    }

    private static List<SessionJournalLogEntry> parseJsonLines(String content, boolean yaml) {
        List<SessionJournalLogEntry> entries = new ArrayList<>();
        for (String rawLine : content.lines().toList()) {
            SessionJournalLogEntry entry = parseJsonLine(rawLine, yaml);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static SessionJournalLogEntry parseJsonLine(String rawLine, boolean yaml) {
        String line = rawLine;
        if (yaml) {
            if (!line.startsWith(SessionJournalLogSerializer.Yaml.ENTRY_PREFIX)) {
                return null; // header keys, meta line, end marker
            }
            line = line.substring(SessionJournalLogSerializer.Yaml.ENTRY_PREFIX.length());
        }
        line = line.strip();
        if (line.isEmpty() || !line.startsWith("{")) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (json.has("type")) {
                return null; // meta or end line
            }
            SessionJournalLogEntry.Kind kind = json.has("k")
                ? SessionJournalLogEntry.Kind.fromKey(json.get("k").getAsString())
                : null;
            long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;
            OffsetDateTime timestamp = json.has("t") ? parseTimestamp(json.get("t").getAsString()) : null;
            if (kind == null || seq < 0 || timestamp == null) {
                return null;
            }
            return new SessionJournalLogEntry(
                seq,
                timestamp,
                kind,
                json.has("x") ? json.get("x").getAsString() : "",
                json.has("redacted") && json.get("redacted").getAsBoolean(),
                json.has("partial") && json.get("partial").getAsBoolean(),
                json.has("file") ? json.get("file").getAsString() : null,
                json.has("repeat") ? json.get("repeat").getAsInt() : 1);
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            // torn or foreign line — recovery contract is to skip it
            return null;
        }
    }

    /**
     * Parses individual physical lines of a capture-log part — the streaming counterpart to
     * {@link #readPart(Path)}. Every format keeps the one-entry-per-line invariant, so a scanner
     * can consume a part line by line without materializing it; header, footer, meta and torn
     * lines yield {@code null}.
     */
    public static final class LineParser {

        private final SessionJournalLogFormat format;
        private final XMLInputFactory xmlFactory;

        private LineParser(SessionJournalLogFormat format) {
            this.format = format;
            this.xmlFactory = format == SessionJournalLogFormat.XML ? secureXmlInputFactory() : null;
        }

        /** Parser for the given part file; null when the file name is not a recognized log part. */
        public static LineParser forPartFile(Path partFile) {
            SessionJournalLogFormat format = formatOf(partFile);
            return format != null ? new LineParser(format) : null;
        }

        /** The entry on this line, or null for header/footer/meta/torn lines. */
        public SessionJournalLogEntry parse(String rawLine) {
            if (rawLine == null || rawLine.isEmpty()) {
                return null;
            }
            return switch (format) {
                case JSON -> parseJsonLine(rawLine, false);
                case YAML -> parseJsonLine(rawLine, true);
                case XML -> parseXmlEntryLine(rawLine);
            };
        }

        private SessionJournalLogEntry parseXmlEntryLine(String rawLine) {
            String line = rawLine.strip();
            if (line.length() < 3 || line.charAt(0) != '<' || line.charAt(1) == '?' || line.charAt(1) == '/') {
                return null; // prolog, footer, or not an element at all
            }
            int nameEnd = 1;
            while (nameEnd < line.length() && Character.isLetter(line.charAt(nameEnd))) {
                nameEnd++;
            }
            if (SessionJournalLogEntry.Kind.fromKey(line.substring(1, nameEnd)) == null) {
                return null; // root element or meta line
            }
            try {
                // An entry line is a complete single-root document (text escapes '<' and '>').
                List<SessionJournalLogEntry> parsed = parseXmlStrict(line, xmlFactory);
                return parsed.isEmpty() ? null : parsed.get(0);
            } catch (XMLStreamException e) {
                return null; // torn line — recovery contract is to skip it
            }
        }
    }

    private static long parseLong(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static OffsetDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, TIMESTAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
