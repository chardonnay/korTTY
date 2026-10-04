package de.kortty.jobscheduler;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.policy.EffectivePolicy;
import de.kortty.telemetry.JobNotificationTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sends a finished run to the job's webhook targets. Everything after the hand-over runs on the
 * {@link WebhookSender}'s executor: looking the target up, decrypting its URL with the master
 * password, formatting the payload and the delivery with its retries. A target that is disabled,
 * deleted or has no URL is left out; a URL that cannot be used (master password locked,
 * unreadable, rejected) and a delivery that finally failed are written to the run journal as a
 * system entry naming the job, the target and at most the receiver's host.
 *
 * <p>The enterprise policy is consulted on every send ({@link #policyBlock}): while it denies
 * {@code job-webhooks}, or the receiver's host is outside its webhook host allowlist, nothing is
 * sent and the run journal records {@link #BLOCKED_BY_POLICY}.
 */
public final class JobWebhookNotifier {

    private static final Logger logger = LoggerFactory.getLogger(JobWebhookNotifier.class);

    /** The journal summary of a delivery that failed after its retries. */
    public static final String FAILED_DELIVERY = "notification failed: webhook delivery unsuccessful";
    /** The journal summary of a delivery the full or closed send queue dropped. */
    public static final String DROPPED_DELIVERY = "notification dropped: webhook send queue full or closed";

    /** The journal summary of a delivery the enterprise policy did not allow. */
    public static final String BLOCKED_BY_POLICY = "notification blocked by policy";

    private static final int MAX_NAME_CHARS = 80;

    /** Why the policy stops a webhook delivery. */
    public enum PolicyBlock {
        /** The policy denies {@code job-webhooks}. */
        FEATURE_DENIED("Policy: job-webhooks denied"),
        /** The receiver's host is not in {@code webhook-host-allowlist}. */
        HOST_NOT_ALLOWED("Policy: host not in webhook-host-allowlist");

        private final String journalText;

        PolicyBlock(String journalText) {
            this.journalText = journalText;
        }

        /** The fixed text the journal entry's detail carries. */
        public String journalText() {
            return journalText;
        }
    }

    private final Function<String, Optional<WebhookTarget>> targetLookup;
    private final WebhookTargetSecrets secrets;
    private final Supplier<char[]> masterPassword;
    private final WebhookPayloadFormatter formatter;
    private final WebhookSender sender;
    private final Consumer<JobJournalEntry> journal;
    private final Supplier<EffectivePolicy> policy;

    /**
     * @param targetLookup   the stored webhook target by id
     * @param secrets        decrypts a target's URL
     * @param masterPassword the unlocked master password; {@code null} or empty while locked
     * @param formatter      builds the payloads
     * @param sender         delivers them on its own executor
     * @param journal        records a skipped or failed delivery in the run journal
     * @param policy         the current enterprise policy, consulted on every send
     */
    public JobWebhookNotifier(Function<String, Optional<WebhookTarget>> targetLookup, WebhookTargetSecrets secrets,
            Supplier<char[]> masterPassword, WebhookPayloadFormatter formatter, WebhookSender sender,
            Consumer<JobJournalEntry> journal, Supplier<EffectivePolicy> policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.targetLookup = Objects.requireNonNull(targetLookup, "targetLookup");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.masterPassword = Objects.requireNonNull(masterPassword, "masterPassword");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /**
     * Hands the run to the sender's executor for each of {@code targetIds}; returns at once. Nothing
     * happens for an empty list.
     */
    public void notify(JobRunEvent event, Collection<String> targetIds) {
        if (event == null || targetIds == null || targetIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(new ArrayList<>(targetIds));
        if (!sender.execute(() -> ids.forEach(id -> deliverTo(event, id)))) {
            logger.warn("Webhook notifications for job {} dropped: the send queue is full or closed", event.jobId());
            record(event, null, JobRunStatus.FAILED, DROPPED_DELIVERY, null);
            for (String id : ids) {
                targetLookup.apply(id).filter(WebhookTarget::isEnabled)
                    .ifPresent(target -> track(target, JobNotificationTelemetry.Outcome.FAILED, 0));
            }
        }
    }

    /**
     * Journals {@link #BLOCKED_BY_POLICY} for each enabled target of {@code targetIds} without
     * handing anything to the sender; for a policy that denies {@code job-webhooks} as a whole, so
     * not even a URL is decrypted.
     */
    public void recordBlockedByPolicy(JobRunEvent event, Collection<String> targetIds, PolicyBlock reason) {
        if (event == null || targetIds == null || reason == null) {
            return;
        }
        for (String id : targetIds) {
            Optional<WebhookTarget> found = id != null ? targetLookup.apply(id) : Optional.empty();
            if (found.isPresent() && found.get().isEnabled()) {
                logger.info("Webhook notification for job {} blocked by policy: {}", event.jobId(), reason);
                record(event, found.get(), JobRunStatus.BLOCKED, BLOCKED_BY_POLICY, reason.journalText());
                track(found.get(), JobNotificationTelemetry.Outcome.BLOCKED, 0);
            }
        }
    }

    /**
     * Whether the policy stops a webhook to {@code uri}; empty when it may be sent. A missing policy
     * counts as unrestricted. Every send asks, the "Send test" button included.
     */
    public static Optional<PolicyBlock> policyBlock(EffectivePolicy policy, URI uri) {
        EffectivePolicy effective = policy != null ? policy : EffectivePolicy.unrestricted();
        if (!effective.jobWebhooksAllowed()) {
            return Optional.of(PolicyBlock.FEATURE_DENIED);
        }
        String host = uri != null ? uri.getHost() : null;
        if (!effective.webhookHostAllowlist().allows(host)) {
            return Optional.of(PolicyBlock.HOST_NOT_ALLOWED);
        }
        return Optional.empty();
    }

    /**
     * Delivers the run to one target on the calling thread and journals a skip or failure; for the
     * sender's executor (and tests) only.
     *
     * @return the delivery's result, or empty when the target was left out or skipped
     */
    Optional<WebhookSender.Result> deliverTo(JobRunEvent event, String targetId) {
        Optional<WebhookTarget> found = targetId != null ? targetLookup.apply(targetId) : Optional.empty();
        if (found.isEmpty() || !found.get().isEnabled()) {
            return Optional.empty();
        }
        WebhookTarget target = found.get();
        if (!currentPolicy().jobWebhooksAllowed()) {
            logger.info("Webhook notification for job {} blocked by policy: {}", event.jobId(),
                PolicyBlock.FEATURE_DENIED);
            record(event, target, JobRunStatus.BLOCKED, BLOCKED_BY_POLICY, PolicyBlock.FEATURE_DENIED.journalText());
            track(target, JobNotificationTelemetry.Outcome.BLOCKED, 0);
            return Optional.empty();
        }
        WebhookTargetSecrets.Resolution resolution = secrets.resolve(target, masterPassword.get());
        if (!resolution.resolved()) {
            logger.info("Webhook notification for job {} skipped: {}", event.jobId(), resolution.skipReason());
            record(event, target, JobRunStatus.BLOCKED, resolution.journalText(), null);
            track(target, JobNotificationTelemetry.Outcome.BLOCKED, 0);
            return Optional.empty();
        }
        Optional<PolicyBlock> block = policyBlock(currentPolicy(), resolution.uri());
        if (block.isPresent()) {
            String host = WebhookSender.host(resolution.uri());
            logger.info("Webhook notification for job {} to host {} blocked by policy: {}", event.jobId(), host,
                block.get());
            record(event, target, JobRunStatus.BLOCKED, BLOCKED_BY_POLICY,
                "Host: " + host + " · " + block.get().journalText());
            track(target, JobNotificationTelemetry.Outcome.BLOCKED, 0);
            return Optional.empty();
        }
        String payload = formatter.format(target, event, null);
        WebhookSender.Result result = sender.deliver(resolution.uri(), payload);
        if (!result.delivered()) {
            String detail = "Host: " + WebhookSender.host(resolution.uri()) + " · attempts: " + result.attempts()
                + (result.problem() != null ? " · " + result.problem() : "");
            record(event, target, JobRunStatus.FAILED,
                result.outcome() == WebhookSender.Outcome.DROPPED ? DROPPED_DELIVERY : FAILED_DELIVERY, detail);
        }
        track(target, result.delivered() ? JobNotificationTelemetry.Outcome.OK : JobNotificationTelemetry.Outcome.FAILED,
            result.attempts());
        return Optional.of(result);
    }

    /** The anonymous-statistics id of a payload format. */
    public static JobNotificationTelemetry.Format telemetryFormat(WebhookFormat format) {
        if (format == null) {
            return JobNotificationTelemetry.Format.GENERIC;
        }
        return switch (format) {
            case SLACK -> JobNotificationTelemetry.Format.SLACK;
            case TEAMS -> JobNotificationTelemetry.Format.TEAMS;
            case GENERIC_JSON -> JobNotificationTelemetry.Format.GENERIC;
        };
    }

    private static void track(WebhookTarget target, JobNotificationTelemetry.Outcome outcome, int attempts) {
        JobNotificationTelemetry.track(JobNotificationTelemetry.Channel.WEBHOOK, telemetryFormat(target.getFormat()),
            outcome, attempts);
    }

    private EffectivePolicy currentPolicy() {
        EffectivePolicy current = policy.get();
        return current != null ? current : EffectivePolicy.unrestricted();
    }

    private void record(JobRunEvent event, WebhookTarget target, JobRunStatus status, String summary, String extra) {
        StringBuilder detail = new StringBuilder("Job: ")
            .append(DisplayTextSanitizer.sanitize(event.jobName(), MAX_NAME_CHARS));
        if (target != null) {
            String name = DisplayTextSanitizer.sanitize(target.getName(), MAX_NAME_CHARS);
            detail.append(" · Webhook target: ").append(name.isEmpty() ? target.getId() : name);
        }
        if (extra != null) {
            detail.append(" · ").append(extra);
        }
        try {
            journal.accept(JobJournalEntry.system(status, summary, detail.toString()));
        } catch (RuntimeException e) {
            logger.warn("Could not journal a webhook notification result for job {}", event.jobId(), e);
        }
    }
}
