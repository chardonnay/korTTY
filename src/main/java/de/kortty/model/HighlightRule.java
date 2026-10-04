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
 *
 * <p>A rule can also be a <em>trigger</em> ({@link #getAction()}): besides, or instead of, changing how
 * the match looks, it acts when the pattern appears in new output — a desktop notification
 * ({@link Action#NOTIFY}) or running one of the user's snippets ({@link Action#RUN_SNIPPET}, the one
 * {@link #getSnippetId()} names). The notification says the rule's {@link #getName() name}; the matched
 * text only when {@link #isNotifyWithText()} is set.
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

    /**
     * What a rule does, besides changing how its match looks, when its pattern appears in output that
     * arrives in a terminal pane. Never anything that types text taken from the output, and never an
     * answer to a prompt: a server decides what it prints, so an action must not turn its output into
     * keystrokes. The one action that types, {@link #RUN_SNIPPET}, types a snippet the user chose and
     * confirmed for the connection.
     */
    @XmlType(name = "HighlightRuleAction")
    @XmlEnum
    public enum Action {
        /** Only highlight. */
        NONE,
        /** Also show a desktop notification (and mark the tab) while the pane is not in view. */
        NOTIFY,
        /**
         * Also run the snippet {@link HighlightRule#getSnippetId()} names in the pane, as Send to
         * Terminal does — after a confirmation per connection, with a cooldown and a loop guard.
         */
        RUN_SNIPPET
    }

    @XmlElement
    private String id;

    /** What the rule is called in a notification; optional, the pattern stands in when blank. */
    @XmlElement
    private String name;

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

    @XmlElement
    private Action action = Action.NONE;

    /** Put the matched text into the notification (cleaned and shortened); off = the rule's name only. */
    @XmlElement
    private boolean notifyWithText;

    /** The id of the snippet {@link Action#RUN_SNIPPET} runs; ignored for every other action. */
    @XmlElement
    private String snippetId;

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
            this.name = other.name;
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
            this.action = other.action;
            this.notifyWithText = other.notifyWithText;
            this.snippetId = other.snippetId;
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

    /** The rule's name as typed, or {@code null} when it has none (blank counts as none). */
    public String getName() {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void setName(String name) {
        this.name = name;
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

    /** {@link Action#NONE} when the file has no value. */
    public Action getAction() {
        return action != null ? action : Action.NONE;
    }

    public void setAction(Action action) {
        this.action = action != null ? action : Action.NONE;
    }

    public boolean isNotifyWithText() {
        return notifyWithText;
    }

    public void setNotifyWithText(boolean notifyWithText) {
        this.notifyWithText = notifyWithText;
    }

    /**
     * The id of the snippet the rule runs ({@link Action#RUN_SNIPPET}), or {@code null} when it names
     * none (blank counts as none). Kept when the action changes, so switching back restores the choice.
     */
    public String getSnippetId() {
        if (snippetId == null) {
            return null;
        }
        String trimmed = snippetId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void setSnippetId(String snippetId) {
        this.snippetId = snippetId;
    }

    /** True when the rule changes how its match looks: a color or one of bold, italic, underline. */
    public boolean hasVisualEffect() {
        return getForeground() != null || getBackground() != null || bold || italic || underline;
    }

    /** True when the rule is a trigger: it acts when its pattern appears ({@link #getAction()}). */
    public boolean hasAction() {
        return getAction() != Action.NONE;
    }

    private static String normalizeColor(String color) {
        if (color == null) {
            return null;
        }
        String trimmed = color.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
