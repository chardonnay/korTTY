package de.kortty.core;

import de.kortty.model.SessionJournalReplacement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Known-secret literal redaction for captured terminal output. Seeded with the connection's own
 * vault credentials so at least the secrets korTTY already stores can never reach a log file, and
 * with the organisation's {@code [[rule.session-journal.replace]]} rules when a policy defines any.
 *
 * <p>Applied on the capture thread BEFORE a line is buffered or enqueued — house rule: the secret
 * must never flow into the string that gets written (never construct-then-mask).</p>
 *
 * <p>Used by both capture features: {@link SessionJournalSession} and {@link TerminalLogger}. The
 * name still says journal because that is where it started and renaming it would churn every
 * caller; the behaviour has nothing journal-specific about it.</p>
 */
public final class SessionJournalRedactor {

    /** Very short secrets would redact ordinary text (e.g. a one-letter "a"); ignore them. */
    private static final int MIN_SECRET_LENGTH = 4;

    public static final String REPLACEMENT = "***";

    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private volatile SessionJournalReplacer replacer = SessionJournalReplacer.none();

    public void addSecret(String secret) {
        if (secret == null) {
            return;
        }
        String value = secret.strip();
        if (value.length() >= MIN_SECRET_LENGTH && !secrets.contains(value)) {
            secrets.add(value);
        }
    }

    /** Installs the policy-mandated search-and-replace rules; replaces any previous set. */
    public void setReplacements(List<SessionJournalReplacement> replacements) {
        this.replacer = SessionJournalReplacer.of(replacements);
    }

    /** True when a policy mandates automatic replacements for this journal. */
    public boolean hasReplacements() {
        return !replacer.isEmpty();
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, REPLACEMENT);
        }
        // Policy rules run last so an admin pattern also catches what the known-secret pass left.
        return replacer.apply(result);
    }

    /**
     * Same masking as {@link #redact}, plus how many occurrences it replaced — for callers that
     * tell the user something was masked (the AI request preview). The capture path keeps using
     * {@link #redact}, which does not pay for counting.
     */
    public RedactionResult redactCounting(String text) {
        return redactCounting(text, null);
    }

    /**
     * Same as {@link #redactCounting(String)}, and hands every masked value to
     * {@code maskedValues} (once per known secret found, once per policy-rule match) — for a
     * caller that counts distinct secrets across several texts. The values are secrets: the
     * consumer must not keep or log them as they are.
     */
    public RedactionResult redactCounting(String text, Consumer<String> maskedValues) {
        if (text == null || text.isEmpty()) {
            return RedactionResult.unchanged(text);
        }
        String result = text;
        int count = 0;
        for (String secret : secrets) {
            int occurrences = countOccurrences(result, secret);
            if (occurrences > 0) {
                result = result.replace(secret, REPLACEMENT);
                count += occurrences;
                if (maskedValues != null) {
                    maskedValues.accept(secret);
                }
            }
        }
        return new RedactionResult(result, count).then(replacer.applyCounting(result, maskedValues));
    }

    /**
     * Adds every known secret of {@code other} to this redactor (its policy rules are not
     * copied). Lets a caller build one redactor for a run from several sources — the terminal
     * tab, the command runner, a sudo password typed during the run — without changing theirs.
     */
    public void addSecretsFrom(SessionJournalRedactor other) {
        if (other == null || other == this) {
            return;
        }
        for (String secret : other.secrets) {
            addSecret(secret);
        }
    }

    /** Non-overlapping, left to right — the occurrences {@link String#replace} replaces. */
    private static int countOccurrences(String text, String secret) {
        int count = 0;
        int index = text.indexOf(secret);
        while (index >= 0) {
            count++;
            index = text.indexOf(secret, index + secret.length());
        }
        return count;
    }
}
