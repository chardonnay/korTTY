package de.kortty.jobscheduler;

import de.kortty.policy.EffectivePolicy;

import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The "Send test" button of the webhook-target manager: one sample run, sent once to one target.
 * It is the only notification a user triggers by hand, and it goes through the same checks as a
 * real run: the URL rules, the master password for a stored URL, the enterprise policy (feature
 * and host allowlist) and the target's payload format. Everything runs on the
 * {@link WebhookSender}'s executor, never on the FX thread; the outcome names at most the
 * receiver's host.
 */
public final class WebhookTestSend {

    /** i18n prefix of the outcome texts. */
    public static final String KEY_PREFIX = "jobscheduler.dialog.webhook.test.";

    /** The id the sample run carries; no job has it. */
    static final String TEST_JOB_ID = "__webhook_test__";

    /** How a test send ended. */
    public enum Kind {
        DELIVERED("ok"),
        FAILED("failed"),
        DROPPED("dropped"),
        INVALID_URL(null),
        NO_URL("noUrl"),
        LOCKED("locked"),
        UNREADABLE("unreadable"),
        FEATURE_DENIED("featureDenied"),
        HOST_NOT_ALLOWED("hostBlocked");

        private final String keySuffix;

        Kind(String keySuffix) {
            this.keySuffix = keySuffix;
        }
    }

    /**
     * @param kind       how it ended
     * @param host       the receiver's host, when the URL was readable
     * @param attempts   delivery attempts made
     * @param problem    the sender's short failure reason, or the URL problem's i18n key for
     *                   {@link Kind#INVALID_URL}
     */
    public record Outcome(Kind kind, String host, int attempts, String problem) {

        public boolean delivered() {
            return kind == Kind.DELIVERED;
        }

        /** The i18n key of the text the dialog shows. */
        public String messageKey() {
            return kind == Kind.INVALID_URL ? problem : KEY_PREFIX + kind.keySuffix;
        }

        /** The arguments of {@link #messageKey()}: host, attempts and problem. */
        public Object[] messageArgs() {
            return new Object[] {host != null ? host : "", String.valueOf(attempts), problem != null ? problem : ""};
        }
    }

    private WebhookTestSend() {
    }

    /**
     * Sends the sample run to {@code target} in the background.
     *
     * @param typedUrl       a URL typed into the editor and not saved yet; {@code null} or blank to
     *                       use the target's stored URL
     * @param masterPassword the unlocked master password, needed only for a stored URL; the array
     *                       is copied, so the caller may clear its own
     * @param jobName        the job name the sample shows
     * @return the outcome; never completes exceptionally
     */
    public static CompletableFuture<Outcome> send(WebhookTarget target, String typedUrl, WebhookTargetSecrets secrets,
            char[] masterPassword, EffectivePolicy policy, WebhookPayloadFormatter formatter, WebhookSender sender,
            Clock clock, String jobName, String summary) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sender, "sender");
        WebhookTarget snapshot = target.copy();
        char[] master = masterPassword != null ? masterPassword.clone() : null;
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        boolean queued = sender.execute(() -> {
            try {
                result.complete(sendNow(snapshot, typedUrl, secrets, master, policy, formatter, sender, clock, jobName,
                    summary));
            } catch (RuntimeException e) {
                result.complete(new Outcome(Kind.FAILED, null, 0, e.getClass().getSimpleName()));
            } finally {
                if (master != null) {
                    Arrays.fill(master, '\0');
                }
            }
        });
        if (!queued) {
            if (master != null) {
                Arrays.fill(master, '\0');
            }
            result.complete(new Outcome(Kind.DROPPED, null, 0, null));
        }
        return result;
    }

    /** {@link #send} on the calling thread; for the sender's executor and tests only. */
    static Outcome sendNow(WebhookTarget target, String typedUrl, WebhookTargetSecrets secrets, char[] masterPassword,
            EffectivePolicy policy, WebhookPayloadFormatter formatter, WebhookSender sender, Clock clock, String jobName,
            String summary) {
        EffectivePolicy effective = policy != null ? policy : EffectivePolicy.unrestricted();
        if (!effective.jobWebhooksAllowed()) {
            return new Outcome(Kind.FEATURE_DENIED, null, 0, null);
        }
        URI uri;
        if (JobNotificationFormModel.replacesUrl(typedUrl)) {
            WebhookUrlValidator.Result validated = WebhookUrlValidator.validate(typedUrl);
            if (!validated.valid()) {
                return new Outcome(Kind.INVALID_URL, null, 0, validated.problem().i18nKey());
            }
            uri = validated.uri();
        } else {
            WebhookTargetSecrets.Resolution resolution = secrets.resolve(target, masterPassword);
            if (!resolution.resolved()) {
                return new Outcome(switch (resolution.skipReason()) {
                    case LOCKED -> Kind.LOCKED;
                    case NO_URL -> Kind.NO_URL;
                    case UNREADABLE -> Kind.UNREADABLE;
                    case INVALID -> Kind.INVALID_URL;
                }, null, 0, resolution.skipReason() == WebhookTargetSecrets.SkipReason.INVALID
                    ? WebhookUrlValidator.Problem.MALFORMED.i18nKey()
                    : null);
            }
            uri = resolution.uri();
        }
        String host = WebhookSender.host(uri);
        Optional<JobWebhookNotifier.PolicyBlock> block = JobWebhookNotifier.policyBlock(effective, uri);
        if (block.isPresent()) {
            return new Outcome(block.get() == JobWebhookNotifier.PolicyBlock.FEATURE_DENIED
                ? Kind.FEATURE_DENIED : Kind.HOST_NOT_ALLOWED, host, 0, null);
        }
        JobRunEvent sample = sampleEvent(jobName, summary, clock);
        WebhookSender.Result delivery = sender.deliver(uri, formatter.format(target, sample, null));
        Kind kind = switch (delivery.outcome()) {
            case DELIVERED -> Kind.DELIVERED;
            case DROPPED -> Kind.DROPPED;
            case FAILED -> Kind.FAILED;
        };
        return new Outcome(kind, host, delivery.attempts(), delivery.problem());
    }

    /** The sample: a successful manual run of {@code jobName}, finished now, without an exit code. */
    static JobRunEvent sampleEvent(String jobName, String summary, Clock clock) {
        return new JobRunEvent(TEST_JOB_ID, jobName, JobRunStatus.SUCCESS, null, null, "manual", summary,
            (clock != null ? clock : Clock.systemUTC()).instant());
    }
}
