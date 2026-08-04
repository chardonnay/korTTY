package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.UUID;

/**
 * Sets a marker automatically when an entry's text matches. Rules are evaluated in list order and
 * the first enabled match wins — there is deliberately no priority field, because list order is
 * the only precedence a user can reason about without a hidden severity table.
 *
 * <p>A rule never overwrites a marker the user set by hand; see
 * {@link SessionJournalEntry.MarkerSource}.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalMarkerRule")
public class SessionJournalMarkerRule {

    @XmlElement
    private String id;

    /** Id of the {@link SessionJournalMarkerDefinition} to apply. */
    @XmlElement
    private String markerId;

    /** Search term: a literal phrase, or a regular expression when {@link #regex} is set. */
    @XmlElement
    private String pattern;

    @XmlElement
    private boolean regex;

    @XmlElement
    private boolean ignoreCase = true;

    @XmlElement
    private boolean enabled = true;

    public SessionJournalMarkerRule() {
        this.id = UUID.randomUUID().toString();
    }

    public SessionJournalMarkerRule(String markerId, String pattern, boolean regex) {
        this();
        this.markerId = SessionJournalMarkerDefinition.normalizeId(markerId);
        setPattern(pattern);
        this.regex = regex;
    }

    public SessionJournalMarkerRule(SessionJournalMarkerRule other) {
        if (other != null) {
            this.id = other.id;
            this.markerId = other.markerId;
            this.pattern = other.pattern;
            this.regex = other.regex;
            this.ignoreCase = other.ignoreCase;
            this.enabled = other.enabled;
        }
    }

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMarkerId() {
        return markerId;
    }

    public void setMarkerId(String markerId) {
        this.markerId = SessionJournalMarkerDefinition.normalizeId(markerId);
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        String trimmed = pattern != null ? pattern.trim() : "";
        this.pattern = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public void setIgnoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** A rule without a pattern or without a target marker can never do anything useful. */
    public boolean isUsable() {
        return enabled && pattern != null && !pattern.isBlank() && markerId != null && !markerId.isBlank();
    }
}
