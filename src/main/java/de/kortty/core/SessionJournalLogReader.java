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
import java.util.zip.GZIPInputStream;

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

    /** Finds a part file in any format, compressed or not; null when the part does not exist. */
    public static Path findPartFile(Path journalDir, int part) {
        for (SessionJournalLogFormat format : SessionJournalLogFormat.values()) {
            Path plain = journalDir.resolve(partFileName(part, format));
            if (Files.isRegularFile(plain)) {
                return plain;
            }
            Path gz = journalDir.resolve(partFileName(part, format) + SessionJournalLogCompressor.GZIP_SUFFIX);
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
        String fileName = partFile.getFileName().toString();
        boolean gzipped = fileName.endsWith(SessionJournalLogCompressor.GZIP_SUFFIX);
        String plainName = gzipped
            ? fileName.substring(0, fileName.length() - SessionJournalLogCompressor.GZIP_SUFFIX.length())
            : fileName;
        SessionJournalLogFormat format = formatFromFileName(plainName);
        if (format == null) {
            return List.of();
        }
        String content = readContent(partFile, gzipped);
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

    private static SessionJournalLogFormat formatFromFileName(String plainName) {
        int dot = plainName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return SessionJournalLogFormat.fromKey(plainName.substring(dot + 1));
    }

    private static String readContent(Path file, boolean gzipped) throws IOException {
        if (!gzipped) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
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
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
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
                String text = reader.getElementText();
                if (seq >= 0 && timestamp != null) {
                    entries.add(new SessionJournalLogEntry(seq, timestamp, kind, text, redacted, partial, file));
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
            String line = rawLine;
            if (yaml) {
                if (!line.startsWith(SessionJournalLogSerializer.Yaml.ENTRY_PREFIX)) {
                    continue; // header keys, meta line, end marker
                }
                line = line.substring(SessionJournalLogSerializer.Yaml.ENTRY_PREFIX.length());
            }
            line = line.strip();
            if (line.isEmpty() || !line.startsWith("{")) {
                continue;
            }
            try {
                JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                if (json.has("type")) {
                    continue; // meta or end line
                }
                SessionJournalLogEntry.Kind kind = json.has("k")
                    ? SessionJournalLogEntry.Kind.fromKey(json.get("k").getAsString())
                    : null;
                long seq = json.has("seq") ? json.get("seq").getAsLong() : -1;
                OffsetDateTime timestamp = json.has("t") ? parseTimestamp(json.get("t").getAsString()) : null;
                if (kind == null || seq < 0 || timestamp == null) {
                    continue;
                }
                entries.add(new SessionJournalLogEntry(
                    seq,
                    timestamp,
                    kind,
                    json.has("x") ? json.get("x").getAsString() : "",
                    json.has("redacted") && json.get("redacted").getAsBoolean(),
                    json.has("partial") && json.get("partial").getAsBoolean(),
                    json.has("file") ? json.get("file").getAsString() : null));
            } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
                // torn or foreign line — recovery contract is to skip it
            }
        }
        return entries;
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
