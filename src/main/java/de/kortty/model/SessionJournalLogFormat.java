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
     * <p>JSON Lines, chosen because log tooling ingests it without a parser of its own — <em>not</em>
     * because it is smaller. Measured over 500 entries per case:</p>
     *
     * <ul>
     *   <li>Ordinary terminal output: XML is ~9 bytes per entry smaller, because
     *       {@code <out seq="1" t="…">} beats {@code {"seq":1,"t":"…","k":"out","x":"…"}}.</li>
     *   <li>Output heavy in {@code < > & "}: JSON turns ~10 % smaller, because XML escaping
     *       expands each of those to four or five characters while JSON escapes only quote and
     *       backslash.</li>
     *   <li>Gzipped — which is what a finished log part and every export actually store — all
     *       three land within 2 % of each other, in both directions.</li>
     * </ul>
     *
     * <p>So size is not a reason to prefer any of them; an earlier version of this comment claimed
     * JSON won after gzip, which does not reproduce. YAML is JSON flow mappings with a {@code "- "}
     * prefix: two bytes per line on top of JSON, and no readability in return.</p>
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
