package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarkerDefinition;
import de.kortty.model.SessionJournalMarkerRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies the user's auto-marker rules to journal entries. Pure and side-effect free apart from
 * the entry it is handed, so the matching, the ordering and the safety limits are unit-testable
 * without a journal on disk.
 *
 * <p>Precedence is USER &gt; RULE &gt; AI: a rule may overwrite a marker the AI suggested — a rule
 * is an explicit, deterministic instruction and a category is a heuristic — but it never touches
 * one the user set by hand.</p>
 */
public final class SessionJournalMarkerRules {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalMarkerRules.class);

    /**
     * Longest haystack we hand to a pattern. The excerpts are already the summarizer's 5-line
     * preview, so this only bites on hand-edited entries — but it bounds the input regardless.
     */
    public static final int MAX_MATCH_INPUT_CHARS = 20_000;

    /** Budget for one entry on the live journal-append path, where a hang would stall capture. */
    public static final long LIVE_BUDGET_MILLIS = 250;

    /** Budget per entry for the on-demand pass, where the user is waiting on a progress hint. */
    public static final long BATCH_BUDGET_MILLIS = 1_000;

    /** How often the bounded sequence checks the clock; often enough to stop, rare enough to be free. */
    private static final int BUDGET_CHECK_INTERVAL = 1024;

    public record Compiled(SessionJournalMarkerRule rule, Pattern pattern) {
    }

    private SessionJournalMarkerRules() {
    }

    /** Compiles the usable rules in order; unusable and uncompilable ones are dropped with a warning. */
    public static List<Compiled> compile(List<SessionJournalMarkerRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<Compiled> compiled = new ArrayList<>(rules.size());
        for (SessionJournalMarkerRule rule : rules) {
            if (rule == null || !rule.isUsable()) {
                continue;
            }
            Pattern pattern = compile(rule);
            if (pattern != null) {
                compiled.add(new Compiled(rule, pattern));
            }
        }
        return compiled;
    }

    /** The compiled pattern of one rule, or {@code null} when it does not compile. */
    public static Pattern compile(SessionJournalMarkerRule rule) {
        if (rule == null || rule.getPattern() == null || rule.getPattern().isBlank()) {
            return null;
        }
        int flags = rule.isIgnoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            return Pattern.compile(
                rule.isRegex() ? rule.getPattern() : Pattern.quote(rule.getPattern()), flags);
        } catch (PatternSyntaxException e) {
            // The message quotes the pattern, which the user typed themselves and is not a secret.
            logger.warn("Ignoring session journal marker rule with an invalid pattern: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The text a rule is matched against: title, summary, user note and both excerpts. The
     * capture log is deliberately not included — a rules pass must never read a multi-megabyte
     * file, and the excerpts are the redacted preview the summarizer already produced.
     */
    public static String matchText(SessionJournalEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        append(sb, entry.getTitle());
        append(sb, entry.getText());
        append(sb, entry.getUserNote());
        for (String line : entry.getInputExcerpt()) {
            append(sb, line);
        }
        for (String line : entry.getOutputExcerpt()) {
            append(sb, line);
        }
        if (sb.length() > MAX_MATCH_INPUT_CHARS) {
            sb.setLength(MAX_MATCH_INPUT_CHARS);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isEmpty() || sb.length() >= MAX_MATCH_INPUT_CHARS) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(value);
    }

    /**
     * The first rule that matches, or {@code null}. List order is the priority. A rule that blows
     * the time budget is treated as a non-match and logged once — the length cap alone does not
     * bound catastrophic backtracking.
     */
    public static SessionJournalMarkerRule firstMatch(List<Compiled> rules, String haystack, long budgetMillis) {
        if (rules == null || rules.isEmpty() || haystack == null || haystack.isEmpty()) {
            return null;
        }
        for (Compiled compiled : rules) {
            long deadline = System.nanoTime() + Math.max(1L, budgetMillis) * 1_000_000L;
            try {
                Matcher matcher = compiled.pattern().matcher(new BoundedCharSequence(haystack, deadline));
                if (matcher.find()) {
                    return compiled.rule();
                }
            } catch (MatchBudgetExceeded e) {
                logger.warn("Session journal marker rule '{}' exceeded its match budget and was skipped",
                    compiled.rule().getPattern());
            }
        }
        return null;
    }

    /**
     * Applies the first matching rule to the entry. Returns true when the entry changed.
     * A marker the user set by hand is never overwritten unless {@code overwriteManual} is set.
     */
    public static boolean apply(SessionJournalEntry entry, List<Compiled> rules,
                                List<SessionJournalMarkerDefinition> registry, long budgetMillis,
                                boolean overwriteManual) {
        if (entry == null || rules == null || rules.isEmpty()) {
            return false;
        }
        if (!overwriteManual && entry.getMarkerSource() == SessionJournalEntry.MarkerSource.USER) {
            return false;
        }
        SessionJournalMarkerRule match = firstMatch(rules, matchText(entry), budgetMillis);
        if (match == null) {
            return false;
        }
        SessionJournalMarkerDefinition definition = SessionJournalMarkers.byId(match.getMarkerId(), registry);
        if (definition == null) {
            return false;
        }
        String previousId = entry.getMarkerId();
        de.kortty.model.SessionJournalMarker previousMarker = entry.getMarker();
        SessionJournalMarkers.apply(entry, definition);
        boolean changed = !java.util.Objects.equals(previousId, entry.getMarkerId())
            || previousMarker != entry.getMarker()
            || entry.getMarkerSource() != SessionJournalEntry.MarkerSource.RULE;
        entry.setMarkerSource(SessionJournalEntry.MarkerSource.RULE);
        return changed;
    }

    /** Convenience for the live path: no manual overwrite, live budget. */
    public static boolean apply(SessionJournalEntry entry, List<Compiled> rules,
                                List<SessionJournalMarkerDefinition> registry) {
        return apply(entry, rules, registry, LIVE_BUDGET_MILLIS, false);
    }

    /**
     * Runs the rules over every entry of a document, snapshotting the definitions it applies.
     * Returns how many entries changed.
     */
    public static int applyAll(SessionJournalDocument document, List<Compiled> rules,
                               List<SessionJournalMarkerDefinition> registry, boolean overwriteManual) {
        if (document == null || rules == null || rules.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (SessionJournalEntry entry : document.getEntries()) {
            if (apply(entry, rules, registry, BATCH_BUDGET_MILLIS, overwriteManual)) {
                changed++;
                SessionJournalMarkerDefinition applied =
                    SessionJournalMarkers.byId(entry.getMarkerId(), registry);
                if (applied != null) {
                    SessionJournalMarkers.snapshot(document, applied);
                }
            }
        }
        return changed;
    }

    /** Thrown out of {@link BoundedCharSequence} when a pattern runs past its deadline. */
    private static final class MatchBudgetExceeded extends RuntimeException {
        MatchBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /**
     * A CharSequence that aborts the match once the deadline passes. The regex engine reads
     * through {@code charAt}, so checking the clock there is the only way to interrupt
     * catastrophic backtracking — an input-length cap bounds the input, not the work.
     */
    private static final class BoundedCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final long deadlineNanos;
        private int countdown = BUDGET_CHECK_INTERVAL;

        BoundedCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (--countdown <= 0) {
                countdown = BUDGET_CHECK_INTERVAL;
                if (System.nanoTime() > deadlineNanos) {
                    throw new MatchBudgetExceeded();
                }
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
