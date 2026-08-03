package de.kortty.core;

import de.kortty.model.SessionJournalReplacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiled form of a list of {@link SessionJournalReplacement} rules.
 *
 * <p>Rules are compiled once and then applied per captured line, so the capture thread never pays
 * for {@link Pattern#compile}. A rule whose pattern does not compile is dropped at construction
 * (the policy loader rejects those before they get here); a rule whose <em>replacement</em> blows up
 * at apply time — a {@code $2} group reference against a one-group pattern, say — falls back to
 * substituting the replacement literally rather than leaving the match in the text. Failing open
 * would mean writing the very string the rule exists to remove.</p>
 */
public final class SessionJournalReplacer {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalReplacer.class);

    private static final SessionJournalReplacer EMPTY = new SessionJournalReplacer(List.of());

    private record Compiled(SessionJournalReplacement rule, Pattern pattern, String replacement) {
    }

    private final List<Compiled> rules;
    /** One warning per broken rule, not one per line. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private SessionJournalReplacer(List<Compiled> rules) {
        this.rules = List.copyOf(rules);
    }

    public static SessionJournalReplacer none() {
        return EMPTY;
    }

    public static SessionJournalReplacer of(List<SessionJournalReplacement> declarations) {
        if (declarations == null || declarations.isEmpty()) {
            return EMPTY;
        }
        List<Compiled> compiled = new ArrayList<>(declarations.size());
        for (SessionJournalReplacement rule : declarations) {
            if (rule == null || rule.isEmpty()) {
                continue;
            }
            Pattern pattern = compile(rule);
            if (pattern == null) {
                continue;
            }
            // Only a regex rule may use $1/\ in its replacement; a literal rule takes the text as typed.
            String replacement = rule.regex()
                ? rule.replacement()
                : Matcher.quoteReplacement(rule.replacement());
            compiled.add(new Compiled(rule, pattern, replacement));
        }
        return compiled.isEmpty() ? EMPTY : new SessionJournalReplacer(compiled);
    }

    /** The compiled pattern of one rule, or null when it does not compile. */
    public static Pattern compile(SessionJournalReplacement rule) {
        if (rule == null || rule.isEmpty()) {
            return null;
        }
        int flags = rule.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            return Pattern.compile(rule.regex() ? rule.pattern() : Pattern.quote(rule.pattern()), flags);
        } catch (PatternSyntaxException e) {
            // The message quotes the pattern, which is admin-authored and not a secret.
            logger.warn("Ignoring session journal replacement with an invalid pattern: {}", e.getMessage());
            return null;
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** The declared rules, for UI hints. */
    public List<SessionJournalReplacement> declarations() {
        return rules.stream().map(Compiled::rule).toList();
    }

    /** Applies every rule in order; returns the text unchanged when nothing matches. */
    public String apply(String text) {
        if (text == null || text.isEmpty() || rules.isEmpty()) {
            return text;
        }
        String result = text;
        for (Compiled compiled : rules) {
            Matcher matcher = compiled.pattern().matcher(result);
            if (!matcher.find()) {
                continue;
            }
            matcher.reset();
            try {
                result = matcher.replaceAll(compiled.replacement());
            } catch (RuntimeException e) {
                result = matcher.replaceAll(Matcher.quoteReplacement(compiled.rule().replacement()));
                warnOnce(compiled, e);
            }
        }
        return result;
    }

    /** How many matches the rules find in this text — used for the dialog's dry run. */
    public int countMatches(String text) {
        if (text == null || text.isEmpty() || rules.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Compiled compiled : rules) {
            Matcher matcher = compiled.pattern().matcher(text);
            while (matcher.find()) {
                total++;
                // A zero-width match would otherwise spin forever on the same index.
                if (matcher.end() == matcher.start() && !matcher.hitEnd()) {
                    matcher.region(matcher.end() + 1, text.length());
                }
            }
        }
        return total;
    }

    private void warnOnce(Compiled compiled, RuntimeException error) {
        if (warned.add(compiled.rule().pattern())) {
            logger.warn("Session journal replacement {} has an invalid replacement string ({}); "
                + "substituting it literally", compiled.rule().describe(), error.getMessage());
        }
    }
}
