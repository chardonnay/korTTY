package de.kortty.jobscheduler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class JobSchedulerSecretRedactor {

    private static final int LIMITED_TEXT_CHARS = 4_000;
    /** Thread-safe: AI-swarm runners add their session password from agent worker threads. */
    private final List<String> secrets = new CopyOnWriteArrayList<>();

    public void addSecret(String secret) {
        if (secret != null && !secret.isBlank() && !secrets.contains(secret)) {
            secrets.add(secret);
        }
    }

    /** The secrets added so far, for masking an AI prompt with the same values. */
    List<String> secrets() {
        return List.copyOf(secrets);
    }

    public String prepare(String text, JournalDetailMode mode) {
        String redacted = redact(text);
        if (mode == JournalDetailMode.FULL || redacted == null || redacted.length() <= LIMITED_TEXT_CHARS) {
            return redacted;
        }
        return redacted.substring(0, LIMITED_TEXT_CHARS) + "\n...[truncated by JobScheduler journal mode]...";
    }

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                result = result.replace(secret, "***");
            }
        }
        return result;
    }
}
