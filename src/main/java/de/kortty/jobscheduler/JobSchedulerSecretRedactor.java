package de.kortty.jobscheduler;

import java.util.ArrayList;
import java.util.List;

public class JobSchedulerSecretRedactor {

    private static final int LIMITED_TEXT_CHARS = 4_000;
    private final List<String> secrets = new ArrayList<>();

    public void addSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
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
