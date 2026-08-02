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
