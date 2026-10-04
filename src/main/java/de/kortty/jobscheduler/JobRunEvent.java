package de.kortty.jobscheduler;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One finished JobScheduler run, published after the run result has been saved.
 *
 * @param jobId          the job's id
 * @param jobName        the job's display name
 * @param status         the run's final status (never {@link JobRunStatus#RUNNING})
 * @param previousStatus the job's last finished status before this run, or {@code null} for its first run
 * @param exitCode       the remote exit code, or {@code null} when the job had none
 * @param trigger        how the run started ({@code manual} or {@code scheduled})
 * @param summary        the run's free-text summary; it may name hosts or paths and is never sent
 *                       anywhere without the target's explicit opt-in and masking
 * @param finishedAt     when the run finished
 */
public record JobRunEvent(
    String jobId,
    String jobName,
    JobRunStatus status,
    JobRunStatus previousStatus,
    Integer exitCode,
    String trigger,
    String summary,
    Instant finishedAt) {

    public JobRunEvent {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(status, "status");
        jobName = jobName != null ? jobName : "";
        trigger = trigger != null ? trigger : "";
        finishedAt = finishedAt != null ? finishedAt : Instant.EPOCH;
    }

    /** A successful run right after a failed or blocked one. */
    public boolean recovered() {
        return status == JobRunStatus.SUCCESS
            && (previousStatus == JobRunStatus.FAILED || previousStatus == JobRunStatus.BLOCKED);
    }

    /**
     * The notification triggers this run matches. A recovery matches both
     * {@link JobNotificationTrigger#RECOVERED} and {@link JobNotificationTrigger#SUCCESS}; a cancelled
     * run matches none.
     */
    public Set<JobNotificationTrigger> matchingTriggers() {
        EnumSet<JobNotificationTrigger> triggers = EnumSet.noneOf(JobNotificationTrigger.class);
        switch (status) {
            case FAILED -> triggers.add(JobNotificationTrigger.FAILED);
            case BLOCKED -> triggers.add(JobNotificationTrigger.BLOCKED);
            case SUCCESS -> {
                triggers.add(JobNotificationTrigger.SUCCESS);
                if (recovered()) {
                    triggers.add(JobNotificationTrigger.RECOVERED);
                }
            }
            case CANCELLED, RUNNING -> {
                // never notifies
            }
        }
        return triggers;
    }
}
