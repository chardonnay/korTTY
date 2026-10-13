package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import de.kortty.model.TerminalLogConfig.LogFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.time.LocalDateTime;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Streaming conversion of korTTY logs; selected physical lines include their complete entry. */
public final class TerminalLogDocument {
    private static final Pattern PLAIN = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\] ?(.*)$");
    private static final int MAX_ENTRY_CHARS = 16 * 1024 * 1024;
    public static final String PLAIN_METADATA = "# kortty-meta ";
    private static final String CONTEXT = "@kortty-context ";
    private static final String CONTEXT_VERSION = "kortty-context-v1";

    private TerminalLogDocument() { }

    public static String header(LogFormat format, Map<String, String> metadata) {
        return switch (format) {
            case XML -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<terminal-log" + attributes(metadata) + ">\n";
            case JSON -> "{\n" + metadata.entrySet().stream()
                .map(e -> "  \"" + e.getKey() + "\": \"" + TerminalLogger.escapeJson(e.getValue()) + "\",\n")
                .collect(java.util.stream.Collectors.joining()) + "  \"entries\": [\n";
            case PLAIN_TEXT -> {
                Map<String, String> plainMetadata = new LinkedHashMap<>(metadata);
                plainMetadata.put("contextEncoding", CONTEXT_VERSION);
                yield PLAIN_METADATA + json(plainMetadata) + "\n";
            }
        };
    }

    public static String entry(LogFormat format, Map<String, String> fields) {
        String text = fields.getOrDefault("line", "");
        String timestamp = fields.getOrDefault("timestamp", "");
        return switch (format) {
            case XML -> "  <entry" + attributes(withoutLine(fields)) + ">" + TerminalLogger.escapeXml(text) + "</entry>";
            case JSON -> "    " + json(fields);
            case PLAIN_TEXT -> "[" + timestamp + "] " + (fields.size() > 2 || text.startsWith(CONTEXT)
                ? CONTEXT + json(withoutLine(fields)) + "\t" : "") + text;
        };
    }

    public static String footer(LogFormat format, long entries) {
        return switch (format) {
            case XML -> "</terminal-log>\n";
            case JSON -> (entries > 0 ? "\n" : "") + "  ]\n}\n";
            case PLAIN_TEXT -> "";
        };
    }

    /** Reads just the bounded preamble; no DNS lookup or full-file parse during catalog scans. */
    public static Map<String, String> metadata(BufferedReader reader, LogFormat format) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            String line = boundedLine(reader, 65536);
            if (line == null || isEntry(line, format)) { break; }
            readMetadata(line, format, result);
            if (!result.isEmpty() && format != LogFormat.JSON) { break; }
        }
        return result;
    }

    public static long convert(BufferedReader in, Writer out, LogFormat source, LogFormat target,
                               String fallback, LogTimeRange range, BitSet selected,
                               BooleanSupplier cancelled) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("connection", fallback != null ? fallback : "");
        StringBuilder pending = null;
        int first = 0;
        int number = 0;
        long count = 0;
        boolean header = false;
        String line;
        while ((line = boundedLine(in, MAX_ENTRY_CHARS)) != null) {
            if (cancelled.getAsBoolean()) { throw new TerminalLogTrimmer.CancelledException(); }
            boolean start = isEntry(line, source);
            boolean end = source == LogFormat.XML && line.strip().equals("</terminal-log>")
                || source == LogFormat.JSON && (line.strip().equals("]") || line.strip().equals("}"));
            if (start || end) {
                if (!header) { out.write(header(target, metadata)); header = true; }
                if (pending != null) {
                    count += writeEntry(out, pending.toString(), source, target, first, number, selected, range, count, CONTEXT_VERSION.equals(metadata.get("contextEncoding")));
                    pending = null;
                }
                if (start) { pending = new StringBuilder(line); first = number; }
            } else if (pending != null) {
                if (pending.length() + line.length() > MAX_ENTRY_CHARS) {
                    throw new IOException("Log entry exceeds the 16 MiB conversion limit");
                }
                pending.append('\n').append(line);
            } else if (!header) {
                readMetadata(line, source, metadata);
            }
            number++;
        }
        if (!header) { out.write(header(target, metadata)); }
        if (pending != null) {
            count += writeEntry(out, pending.toString(), source, target, first, number, selected, range, count, CONTEXT_VERSION.equals(metadata.get("contextEncoding")));
        }
        out.write(footer(target, count));
        out.flush();
        return count;
    }

    private static long writeEntry(Writer out, String raw, LogFormat source, LogFormat target, int first, int end,
                                   BitSet selected, LogTimeRange range, long count, boolean encodedContext) throws IOException {
        if (selected != null && (selected.nextSetBit(first) < 0 || selected.nextSetBit(first) >= end)) { return 0; }
        Map<String, String> fields = parseEntry(raw, source, encodedContext);
        if (range != null) {
            try {
                if (!range.contains(LocalDateTime.parse(fields.get("timestamp"), TerminalLogger.TIMESTAMP_FORMAT))) { return 0; }
            } catch (java.time.format.DateTimeParseException e) { throw new IOException("Invalid entry timestamp", e); }
        }
        if (target == LogFormat.JSON && count > 0) { out.write(",\n"); }
        out.write(entry(target, fields));
        if (target != LogFormat.JSON) { out.write('\n'); }
        return 1;
    }

    private static boolean isEntry(String line, LogFormat format) {
        return switch (format) {
            case PLAIN_TEXT -> PLAIN.matcher(line).matches();
            case XML -> line.stripLeading().startsWith("<entry ");
            case JSON -> line.stripLeading().startsWith("{\"timestamp\"");
        };
    }

    static Map<String, String> parseEntry(String raw, LogFormat format) throws IOException {
        return parseEntry(raw, format, false);
    }

    private static Map<String, String> parseEntry(String raw, LogFormat format, boolean encodedContext) throws IOException {
        return switch (format) {
            case XML -> xml(raw.strip(), "entry");
            case JSON -> parseJson(raw.strip().replaceFirst(",\\s*$", ""));
            case PLAIN_TEXT -> {
                int newline = raw.indexOf('\n');
                var match = PLAIN.matcher(newline < 0 ? raw : raw.substring(0, newline));
                if (!match.matches()) { throw new IOException("Invalid plain log entry"); }
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("timestamp", match.group(1));
                String content = match.group(2) + (newline < 0 ? "" : raw.substring(newline));
                if (encodedContext && content.startsWith(CONTEXT) && content.indexOf('\t') > 0) {
                    int tab = content.indexOf('\t');
                    fields.putAll(parseJson(content.substring(CONTEXT.length(), tab)));
                    content = content.substring(tab + 1);
                }
                fields.put("line", content);
                yield fields;
            }
        };
    }

    private static void readMetadata(String line, LogFormat format, Map<String, String> result) throws IOException {
        String stripped = line.strip();
        if (stripped.contains("<!DOCTYPE") || stripped.contains("<!ENTITY")) { throw new IOException("DTD is forbidden in terminal logs"); }
        if (format == LogFormat.PLAIN_TEXT && line.startsWith(PLAIN_METADATA)) {
            result.putAll(parseJson(line.substring(PLAIN_METADATA.length())));
        } else if (format == LogFormat.XML && stripped.startsWith("<terminal-log ") && stripped.endsWith(">")) {
            result.putAll(xml(stripped.substring(0, stripped.length() - 1) + "/>", "terminal-log"));
        } else if (format == LogFormat.JSON && stripped.startsWith("\"") && !stripped.startsWith("\"entries\"")) {
            result.putAll(parseJson("{" + stripped.replaceFirst(",\\s*$", "") + "}"));
        }
    }

    private static Map<String, String> parseJson(String json) throws IOException {
        try {
            var reader = new com.google.gson.stream.JsonReader(new StringReader(json));
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) { throw new IOException("Trailing log JSON data"); }
            Map<String, String> result = new LinkedHashMap<>();
            for (var field : object.entrySet()) {
                if (!field.getKey().matches("[A-Za-z][A-Za-z0-9_-]*") || !field.getValue().isJsonPrimitive()
                    || !field.getValue().getAsJsonPrimitive().isString()) { throw new IOException("Invalid log field"); }
                result.put(field.getKey(), field.getValue().getAsString());
            }
            return result;
        } catch (JsonParseException | IllegalStateException e) { throw new IOException("Invalid log JSON", e); }
    }

    private static Map<String, String> xml(String text, String root) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External entities forbidden"); });
        try {
            var reader = factory.createXMLStreamReader(new StringReader(text));
            try {
                Map<String, String> fields = new LinkedHashMap<>();
                boolean found = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) { throw new IOException("DTD forbidden"); }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (found || !root.equals(reader.getLocalName())) { throw new IOException("Invalid log XML element"); }
                        found = true;
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            fields.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        if (root.equals("entry")) { fields.put("line", reader.getElementText()); }
                    }
                }
                if (!found) { throw new IOException("Missing log XML element"); }
                return fields;
            } finally { reader.close(); }
        } catch (XMLStreamException e) { throw new IOException("Invalid log XML", e); }
    }

    static String boundedLine(BufferedReader in, int limit) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (line.length() >= limit) { throw new IOException("Log line exceeds conversion limit"); }
            line.append((char) c);
        }
        if (c < 0 && line.isEmpty()) { return null; }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') { line.setLength(line.length() - 1); }
        return line.toString();
    }

    private static Map<String, String> withoutLine(Map<String, String> fields) {
        Map<String, String> result = new LinkedHashMap<>(fields);
        result.remove("line");
        return result;
    }

    private static String json(Map<String, String> fields) {
        return "{" + fields.entrySet().stream().map(e -> "\"" + e.getKey() + "\": \""
            + TerminalLogger.escapeJson(e.getValue()) + "\"").collect(java.util.stream.Collectors.joining(", ")) + "}";
    }

    private static String attributes(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> " " + e.getKey() + "=\"" + TerminalLogger.escapeXml(e.getValue()) + "\"")
            .collect(java.util.stream.Collectors.joining());
    }
}
