package de.kortty.core;

import de.kortty.model.SessionJournalReplacement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Known-secret literal redaction for the session journal. Seeded with the connection's own vault
 * credentials so at least the secrets korTTY already stores can never reach the capture log, and
 * with the organisation's {@code [[rule.session-journal.replace]]} rules when a policy defines any.
 *
 * <p>Applied on the capture thread BEFORE a line is buffered or enqueued — house rule: the secret
 * must never flow into the string that gets written (never construct-then-mask).</p>
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
}
