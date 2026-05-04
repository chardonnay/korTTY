package de.kortty.jobscheduler;

public record ActiveJobSummary(
    String jobId,
    String jobName,
    String runId,
    String startedAt,
    String triggerType,
    boolean cancellationRequested) {

    public ActiveJobSummary(String jobId, String jobName, String runId, String startedAt, String triggerType) {
        this(jobId, jobName, runId, startedAt, triggerType, false);
    }
}
