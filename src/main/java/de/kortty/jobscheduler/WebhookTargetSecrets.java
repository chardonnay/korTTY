package de.kortty.jobscheduler;

import de.kortty.security.EncryptionService;

import java.net.URI;
import java.util.Objects;

/**
 * Encrypts and decrypts webhook URLs with the master password, the same way the archive password
 * of a job is protected. A send while the master password is locked is skipped, never attempted
 * with a stale or empty URL; {@link Resolution#journalText()} is what the run journal records.
 */
public final class WebhookTargetSecrets {

    public static final String SKIPPED_LOCKED = "notification skipped: master password locked";
    public static final String SKIPPED_NO_URL = "notification skipped: webhook URL missing";
    public static final String SKIPPED_UNREADABLE = "notification skipped: webhook URL unreadable";
    public static final String SKIPPED_INVALID = "notification skipped: webhook URL rejected";

    /** Why a target's URL could not be used for a send. */
    public enum SkipReason {
        LOCKED(SKIPPED_LOCKED),
        NO_URL(SKIPPED_NO_URL),
        UNREADABLE(SKIPPED_UNREADABLE),
        INVALID(SKIPPED_INVALID);

        private final String journalText;

        SkipReason(String journalText) {
            this.journalText = journalText;
        }

        public String journalText() {
            return journalText;
        }
    }

    /** The decrypted, re-validated URL, or why the send has to be skipped. */
    public record Resolution(URI uri, SkipReason skipReason) {
        public boolean resolved() {
            return uri != null && skipReason == null;
        }

        public String journalText() {
            return skipReason != null ? skipReason.journalText() : null;
        }
    }

    private final EncryptionService encryptionService;

    public WebhookTargetSecrets(EncryptionService encryptionService) {
        this.encryptionService = Objects.requireNonNull(encryptionService, "encryptionService");
    }

    /**
     * Validates {@code url} and stores its encryption on {@code target}.
     *
     * @throws IllegalArgumentException when the URL is rejected; the message is the
     *     {@link WebhookUrlValidator.Problem#i18nKey()} and never contains the URL
     * @throws IllegalStateException when the master password is locked
     */
    public void storeUrl(WebhookTarget target, String url, char[] masterPassword) throws Exception {
        Objects.requireNonNull(target, "target");
        WebhookUrlValidator.Result result = WebhookUrlValidator.validate(url);
        if (!result.valid()) {
            throw new IllegalArgumentException(result.problem().i18nKey());
        }
        if (masterPassword == null || masterPassword.length == 0) {
            throw new IllegalStateException("Master password is locked; the webhook URL cannot be stored.");
        }
        target.setEncryptedUrl(encryptionService.encryptPassword(result.uri().toString(), masterPassword));
    }

    /** Decrypts and re-validates the target's URL for a send. Never throws. */
    public Resolution resolve(WebhookTarget target, char[] masterPassword) {
        if (target == null || !target.hasUrl()) {
            return new Resolution(null, SkipReason.NO_URL);
        }
        if (masterPassword == null || masterPassword.length == 0) {
            return new Resolution(null, SkipReason.LOCKED);
        }
        String plain;
        try {
            plain = encryptionService.decryptPassword(target.getEncryptedUrl(), masterPassword);
        } catch (Exception e) {
            return new Resolution(null, SkipReason.UNREADABLE);
        }
        WebhookUrlValidator.Result result = WebhookUrlValidator.validate(plain);
        return result.valid()
            ? new Resolution(result.uri(), null)
            : new Resolution(null, SkipReason.INVALID);
    }
}
