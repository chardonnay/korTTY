package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;

/**
 * User-facing categorization of a session journal entry. The AI may suggest a marker for its
 * summaries; a user edit always wins (see {@link SessionJournalEntry.MarkerSource}).
 */
@XmlEnum
public enum SessionJournalMarker {
    NONE,
    INFO,
    IMPORTANT,
    ERROR;

    /** Maps the AI response category ("none", "info", "important", "error") leniently; defaults to NONE. */
    public static SessionJournalMarker fromAiCategory(String category) {
        if (category == null) {
            return NONE;
        }
        return switch (category.trim().toLowerCase()) {
            case "info" -> INFO;
            case "important" -> IMPORTANT;
            case "error" -> ERROR;
            default -> NONE;
        };
    }
}
