package de.kortty.model;

import java.util.Objects;

/**
 * One search-and-replace instruction for a session journal.
 *
 * <p>The same declaration serves two very different callers: a user typing into the viewer's
 * search-and-replace dialog, and an administrator's {@code [[rule.session-journal.replace]]} entry,
 * which korTTY applies automatically to every captured line. It is only the declaration — compiling
 * and applying it is {@code SessionJournalReplacer}'s job, so a rule can be validated and stored
 * without paying for a {@link java.util.regex.Pattern} per line.</p>
 *
 * @param pattern     literal text, or a regular expression when {@code regex} is set
 * @param replacement what matches are replaced with; {@code $1} group references work for regex rules
 * @param regex       true to treat {@code pattern} as a regular expression
 * @param ignoreCase  true to match case-insensitively
 * @param label       optional admin-facing description, shown when korTTY explains a mandated rule
 */
public record SessionJournalReplacement(
    String pattern,
    String replacement,
    boolean regex,
    boolean ignoreCase,
    String label) {

    /** What a rule replaces with when the author did not say. */
    public static final String DEFAULT_REPLACEMENT = "***";

    public SessionJournalReplacement {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (replacement == null) {
            replacement = DEFAULT_REPLACEMENT;
        }
        if (label != null && label.isBlank()) {
            label = null;
        }
    }

    /** A literal, case-sensitive rule — what the viewer's dialog builds by default. */
    public static SessionJournalReplacement literal(String pattern, String replacement) {
        return new SessionJournalReplacement(pattern, replacement, false, false, null);
    }

    /** True when the rule cannot match anything and may be skipped outright. */
    public boolean isEmpty() {
        return pattern.isEmpty();
    }

    /** Short human-readable form for logs and UI hints — never includes the pattern of a secret. */
    public String describe() {
        if (label != null) {
            return label;
        }
        return regex ? "regex rule" : "literal rule";
    }
}
