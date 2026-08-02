package de.kortty.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Known-secret literal redaction for the session journal. Seeded with the connection's own vault
 * credentials so at least the secrets korTTY already stores can never reach the capture log.
 *
 * <p>Applied on the capture thread BEFORE a line is buffered or enqueued — house rule: the secret
 * must never flow into the string that gets written (never construct-then-mask).</p>
 */
public final class SessionJournalRedactor {

    /** Very short secrets would redact ordinary text (e.g. a one-letter "a"); ignore them. */
    private static final int MIN_SECRET_LENGTH = 4;

    public static final String REPLACEMENT = "***";

    private final List<String> secrets = new CopyOnWriteArrayList<>();

    public void addSecret(String secret) {
        if (secret == null) {
            return;
        }
        String value = secret.strip();
        if (value.length() >= MIN_SECRET_LENGTH && !secrets.contains(value)) {
            secrets.add(value);
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, REPLACEMENT);
        }
        return result;
    }
}
