package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

import java.util.UUID;

/**
 * One keyword-highlighting rule: what to look for in terminal output and how to show it.
 *
 * <p>Rules of a {@link HighlightRuleSet} are evaluated in list order and the first rule that claims a
 * character owns it, the same precedence as {@link SessionJournalMarkerRule}: list order is the only
 * priority a user can reason about without a hidden severity table.
 *
 * <p>Colors are stored as text: {@code #RRGGBB} for a fixed color, {@code ansi:0} to {@code ansi:15}
 * for a theme color that follows the terminal palette, or {@code null} to keep the color the program
 * wrote. Validation lives in {@code de.kortty.core.highlight.HighlightRuleValidator}; this class only
 * normalizes blank text to {@code null}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "HighlightRule")
public class HighlightRule {

    /** What a match colors. */
    @XmlType(name = "HighlightRuleScope")
    @XmlEnum
    public enum Scope {
        /** Only the matched characters. */
        MATCH,
        /** The whole logical line (soft-wrapped rows included) the match is on. */
        LINE
    }

    @XmlElement
    private String id;

    @XmlElement
    private boolean enabled = true;

    /** A literal phrase, or a regular expression when {@link #regex} is set. Never trimmed. */
    @XmlElement
    private String pattern;

    @XmlElement
    private boolean regex;

    @XmlElement
    private boolean ignoreCase = true;

    /** Only match when the hit is not glued to a letter, digit or underscore on either side. */
    @XmlElement
    private boolean wholeWord;

    @XmlElement
    private Scope scope = Scope.MATCH;

    @XmlElement
    private String foreground;

    @XmlElement
    private String background;

    @XmlElement
    private boolean bold;

    @XmlElement
    private boolean italic;

    @XmlElement
    private boolean underline;

    public HighlightRule() {
        this.id = UUID.randomUUID().toString();
    }

    public HighlightRule(String pattern, boolean regex) {
        this();
        this.pattern = pattern;
        this.regex = regex;
    }

    /** A copy that keeps the id; {@link HighlightRuleSet#duplicate(String)} assigns fresh ones. */
    public HighlightRule(HighlightRule other) {
        this();
        if (other != null) {
            this.id = other.id;
            this.enabled = other.enabled;
            this.pattern = other.pattern;
            this.regex = other.regex;
            this.ignoreCase = other.ignoreCase;
            this.wholeWord = other.wholeWord;
            this.scope = other.scope;
            this.foreground = other.foreground;
            this.background = other.background;
            this.bold = other.bold;
            this.italic = other.italic;
            this.underline = other.underline;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPattern() {
        return pattern;
    }

    /** Stored as typed: leading or trailing spaces can be part of what the user wants to find. */
    public void setPattern(String pattern) {
        this.pattern = pattern;
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

    public boolean isWholeWord() {
        return wholeWord;
    }

    public void setWholeWord(boolean wholeWord) {
        this.wholeWord = wholeWord;
    }

    /** {@link Scope#MATCH} when the file has no value. */
    public Scope getScope() {
        return scope != null ? scope : Scope.MATCH;
    }

    public void setScope(Scope scope) {
        this.scope = scope != null ? scope : Scope.MATCH;
    }

    /** {@code #RRGGBB}, {@code ansi:N} or {@code null} (keep the program's color). */
    public String getForeground() {
        return normalizeColor(foreground);
    }

    public void setForeground(String foreground) {
        this.foreground = normalizeColor(foreground);
    }

    /** {@code #RRGGBB}, {@code ansi:N} or {@code null} (keep the program's color). */
    public String getBackground() {
        return normalizeColor(background);
    }

    public void setBackground(String background) {
        this.background = normalizeColor(background);
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public boolean isItalic() {
        return italic;
    }

    public void setItalic(boolean italic) {
        this.italic = italic;
    }

    public boolean isUnderline() {
        return underline;
    }

    public void setUnderline(boolean underline) {
        this.underline = underline;
    }

    /** True when the rule changes anything at all: a color or one of bold, italic, underline. */
    public boolean hasVisualEffect() {
        return getForeground() != null || getBackground() != null || bold || italic || underline;
    }

    private static String normalizeColor(String color) {
        if (color == null) {
            return null;
        }
        String trimmed = color.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
