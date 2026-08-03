package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;

/**
 * On-disk format of the session journal capture log. All formats share the same entry fields
 * and the one-entry-per-physical-line invariant that keeps crash recovery line-based.
 */
@XmlEnum
public enum SessionJournalLogFormat {
    /** One escaped XML element per line inside a {@code <session-log>} root. */
    XML("XML", "xml"),
    /** JSON Lines: one JSON object per line (documented JSONL convention, file extension .json). */
    JSON("JSON", "json"),
    /** YAML block sequence of JSON-compatible flow mappings, one {@code - {...}} entry per line. */
    YAML("YAML", "yaml");

    /**
     * Format for new journals when nothing else is configured.
     *
     * <p>JSON Lines, measured against the other two on a realistic session: XML is ~9 bytes per
     * entry smaller uncompressed, but JSON is the smallest once a finished log part is gzipped,
     * and it is what log tooling ingests without a parser of its own. YAML is the largest of the
     * three here — it is JSON flow mappings with a {@code "- "} prefix, so it costs two bytes per
     * line on top of JSON and buys no readability. The spread is a few percent either way; the
     * entry text dominates the file, and compression flattens the rest.</p>
     */
    public static final SessionJournalLogFormat DEFAULT = JSON;

    private final String displayName;
    private final String extension;

    SessionJournalLogFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    /** Parses a policy/settings value ("xml", "json", "yaml"); returns null for unknown values. */
    public static SessionJournalLogFormat fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (SessionJournalLogFormat format : values()) {
            if (format.extension.equalsIgnoreCase(key.trim()) || format.name().equalsIgnoreCase(key.trim())) {
                return format;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
