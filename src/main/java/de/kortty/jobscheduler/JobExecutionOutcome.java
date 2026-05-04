package de.kortty.jobscheduler;

public record JobExecutionOutcome(
    JobRunStatus status,
    String summary,
    int exitCode,
    String stdout,
    String stderr,
    String detail) {

    public static JobExecutionOutcome success(String summary, String stdout, String stderr, String detail) {
        return new JobExecutionOutcome(JobRunStatus.SUCCESS, summary, 0, stdout, stderr, detail);
    }

    public static JobExecutionOutcome failed(String summary, int exitCode, String stdout, String stderr, String detail) {
        return new JobExecutionOutcome(JobRunStatus.FAILED, summary, exitCode, stdout, stderr, detail);
    }

    public static JobExecutionOutcome blocked(String summary, String detail) {
        return new JobExecutionOutcome(JobRunStatus.BLOCKED, summary, -1, null, null, detail);
    }

    public static JobExecutionOutcome cancelled(String summary, String detail) {
        return new JobExecutionOutcome(JobRunStatus.CANCELLED, summary, -1, null, null, detail);
    }
}
