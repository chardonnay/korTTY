package de.kortty.jobscheduler;

import de.kortty.core.DisplayTextSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public final class JobWebhookNotifier {

    private static final Logger logger = LoggerFactory.getLogger(JobWebhookNotifier.class);

    /** The journal summary of a delivery that failed after its retries. */
    public static final String FAILED_DELIVERY = "notification failed: webhook delivery unsuccessful";
    /** The journal summary of a delivery the full or closed send queue dropped. */
    public static final String DROPPED_DELIVERY = "notification dropped: webhook send queue full or closed";

    private static final int MAX_NAME_CHARS = 80;

    private final Function<String, Optional<WebhookTarget>> targetLookup;
    private final WebhookTargetSecrets secrets;
    private final Supplier<char[]> masterPassword;
    private final WebhookPayloadFormatter formatter;
    private final WebhookSender sender;
    private final Consumer<JobJournalEntry> journal;

    /**
     * @param targetLookup   the stored webhook target by id
     * @param secrets        decrypts a target's URL
     * @param masterPassword the unlocked master password; {@code null} or empty while locked
     * @param formatter      builds the payloads
     * @param sender         delivers them on its own executor
     * @param journal        records a skipped or failed delivery in the run journal
     */
    public JobWebhookNotifier(Function<String, Optional<WebhookTarget>> targetLookup, WebhookTargetSecrets secrets,
            Supplier<char[]> masterPassword, WebhookPayloadFormatter formatter, WebhookSender sender,
            Consumer<JobJournalEntry> journal) {
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
        }
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
        WebhookTargetSecrets.Resolution resolution = secrets.resolve(target, masterPassword.get());
        if (!resolution.resolved()) {
            logger.info("Webhook notification for job {} skipped: {}", event.jobId(), resolution.skipReason());
            record(event, target, JobRunStatus.BLOCKED, resolution.journalText(), null);
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
        return Optional.of(result);
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
