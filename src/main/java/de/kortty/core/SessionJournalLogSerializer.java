package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;

import java.time.format.DateTimeFormatter;

/**
 * Serializes session journal capture-log entries to one of the supported on-disk formats.
 * Every format keeps the invariant that one entry is exactly one physical line ending in
 * {@code \n} — crash recovery and live tail reads stay line-based regardless of format.
 */
public interface SessionJournalLogSerializer {

    DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** File extension without dot ("xml", "json", "yaml"). */
    String fileExtension();

    /** Header block written (and flushed) when a log part is opened. Ends with a newline. */
    String header(String journalId, int part, SessionJournalMeta meta, String tabSessionId);

    /** One entry as a single physical line ending in {@code \n}. */
    String entryLine(SessionJournalLogEntry entry);

    /** Footer written only on clean close/rotation; may be empty. */
    String footer();

    static SessionJournalLogSerializer forFormat(SessionJournalLogFormat format) {
        return switch (format != null ? format : SessionJournalLogFormat.XML) {
            case XML -> new Xml();
            case JSON -> new Jsonl();
            case YAML -> new Yaml();
        };
    }

    /** Escaped-XML format: {@code <out seq=".." t="..">text</out>} lines inside a root element. */
    final class Xml implements SessionJournalLogSerializer {

        @Override
        public String fileExtension() {
            return "xml";
        }

        @Override
        public String header(String journalId, int part, SessionJournalMeta meta, String tabSessionId) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<session-log formatVersion=\"1\" journalId=\"").append(escapeAttr(journalId))
                .append("\" part=\"").append(part).append("\">\n");
            sb.append("<meta host=\"").append(escapeAttr(meta.getHost()))
                .append("\" port=\"").append(meta.getPort())
                .append("\" username=\"").append(escapeAttr(meta.getUsername()))
                .append("\" connectionName=\"").append(escapeAttr(meta.getConnectionName()))
                .append("\" appVersion=\"").append(escapeAttr(meta.getAppVersion()))
                .append("\" startedAt=\"")
                .append(meta.getStartedAt() != null ? meta.getStartedAt().format(TIMESTAMP) : "")
                .append("\" tabSessionId=\"").append(escapeAttr(tabSessionId)).append("\"/>\n");
            return sb.toString();
        }

        @Override
        public String entryLine(SessionJournalLogEntry entry) {
            StringBuilder sb = new StringBuilder(64 + (entry.text() != null ? entry.text().length() : 0));
            String tag = entry.kind().key();
            sb.append('<').append(tag)
                .append(" seq=\"").append(entry.seq())
                .append("\" t=\"").append(entry.timestamp().format(TIMESTAMP)).append('"');
            if (entry.redacted()) {
                sb.append(" redacted=\"true\"");
            }
            if (entry.partial()) {
                sb.append(" partial=\"true\"");
            }
            if (entry.file() != null) {
                sb.append(" file=\"").append(escapeAttr(entry.file())).append('"');
            }
            String text = entry.text();
            if (text == null || text.isEmpty()) {
                sb.append("/>\n");
            } else {
                sb.append('>').append(escapeText(text)).append("</").append(tag).append(">\n");
            }
            return sb.toString();
        }

        @Override
        public String footer() {
            return "</session-log>\n";
        }

        static String escapeText(String value) {
            if (value == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '&' -> sb.append("&amp;");
                    case '<' -> sb.append("&lt;");
                    case '>' -> sb.append("&gt;");
                    default -> {
                        // Strip characters illegal in XML 1.0 (C0 controls except tab; the line
                        // assembler never passes \n or \r through).
                        if (c >= 0x20 || c == '\t') {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }

        static String escapeAttr(String value) {
            return escapeText(value).replace("\"", "&quot;");
        }
    }

    /** JSON Lines: one compact Gson object per line, meta as a typed first line. */
    final class Jsonl implements SessionJournalLogSerializer {

        static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

        @Override
        public String fileExtension() {
            return "json";
        }

        @Override
        public String header(String journalId, int part, SessionJournalMeta meta, String tabSessionId) {
            return GSON.toJson(metaObject(journalId, part, meta, tabSessionId)) + "\n";
        }

        @Override
        public String entryLine(SessionJournalLogEntry entry) {
            return GSON.toJson(entryObject(entry)) + "\n";
        }

        @Override
        public String footer() {
            JsonObject end = new JsonObject();
            end.addProperty("type", "end");
            return GSON.toJson(end) + "\n";
        }

        static JsonObject metaObject(String journalId, int part, SessionJournalMeta meta, String tabSessionId) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "meta");
            json.addProperty("formatVersion", 1);
            json.addProperty("journalId", journalId);
            json.addProperty("part", part);
            json.addProperty("host", meta.getHost());
            json.addProperty("port", meta.getPort());
            json.addProperty("username", meta.getUsername());
            json.addProperty("connectionName", meta.getConnectionName());
            json.addProperty("appVersion", meta.getAppVersion());
            if (meta.getStartedAt() != null) {
                json.addProperty("startedAt", meta.getStartedAt().format(TIMESTAMP));
            }
            json.addProperty("tabSessionId", tabSessionId);
            return json;
        }

        static JsonObject entryObject(SessionJournalLogEntry entry) {
            JsonObject json = new JsonObject();
            json.addProperty("seq", entry.seq());
            json.addProperty("t", entry.timestamp().format(TIMESTAMP));
            json.addProperty("k", entry.kind().key());
            if (entry.text() != null && !entry.text().isEmpty()) {
                json.addProperty("x", entry.text());
            }
            if (entry.redacted()) {
                json.addProperty("redacted", true);
            }
            if (entry.partial()) {
                json.addProperty("partial", true);
            }
            if (entry.file() != null) {
                json.addProperty("file", entry.file());
            }
            return json;
        }
    }

    /**
     * YAML block sequence of JSON-compatible flow mappings. JSON is valid YAML, so the entries are
     * emitted through Gson and read back the same way — no YAML library needed.
     */
    final class Yaml implements SessionJournalLogSerializer {

        static final String ENTRY_PREFIX = "- ";
        static final String META_PREFIX = "meta: ";

        @Override
        public String fileExtension() {
            return "yaml";
        }

        @Override
        public String header(String journalId, int part, SessionJournalMeta meta, String tabSessionId) {
            return "formatVersion: 1\n"
                + "journalId: " + Jsonl.GSON.toJson(journalId) + "\n"
                + "part: " + part + "\n"
                + META_PREFIX + Jsonl.GSON.toJson(Jsonl.metaObject(journalId, part, meta, tabSessionId)) + "\n"
                + "entries:\n";
        }

        @Override
        public String entryLine(SessionJournalLogEntry entry) {
            return ENTRY_PREFIX + Jsonl.GSON.toJson(Jsonl.entryObject(entry)) + "\n";
        }

        @Override
        public String footer() {
            return "end: true\n";
        }
    }
}
