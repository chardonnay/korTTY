package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.time.ZonedDateTime;
import java.util.UUID;

@XmlRootElement(name = "journalEntry")
@XmlAccessorType(XmlAccessType.FIELD)
public class JobJournalEntry {

    @XmlElement
    private String id = UUID.randomUUID().toString();

    @XmlElement
    private String jobId;

    @XmlElement
    private String jobName;

    @XmlElement
    private String runId;

    @XmlElement
    private JobRunStatus status = JobRunStatus.SUCCESS;

    @XmlElement
    private String triggerType;

    @XmlElement
    private String startedAt;

    @XmlElement
    private String finishedAt;

    @XmlElement
    private Integer exitCode;

    @XmlElement
    private String summary;

    @XmlElement
    private String stdoutText;

    @XmlElement
    private String stderrText;

    @XmlElement
    private String detailText;

    public static JobJournalEntry system(JobRunStatus status, String summary, String detailText) {
        JobJournalEntry entry = new JobJournalEntry();
        entry.setJobId("__system__");
        entry.setJobName("JobScheduler");
        entry.setRunId(UUID.randomUUID().toString());
        entry.setStatus(status);
        entry.setTriggerType("system");
        String now = ZonedDateTime.now().toString();
        entry.setStartedAt(now);
        entry.setFinishedAt(now);
        entry.setSummary(summary);
        entry.setDetailText(detailText);
        return entry;
    }

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = trimToNull(jobId);
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = trimToNull(jobName);
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = trimToNull(runId);
    }

    public JobRunStatus getStatus() {
        return status != null ? status : JobRunStatus.SUCCESS;
    }

    public void setStatus(JobRunStatus status) {
        this.status = status != null ? status : JobRunStatus.SUCCESS;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = trimToNull(triggerType);
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = trimToNull(startedAt);
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = trimToNull(finishedAt);
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = trimToNull(summary);
    }

    public String getStdoutText() {
        return stdoutText;
    }

    public void setStdoutText(String stdoutText) {
        this.stdoutText = trimToNull(stdoutText);
    }

    public String getStderrText() {
        return stderrText;
    }

    public void setStderrText(String stderrText) {
        this.stderrText = trimToNull(stderrText);
    }

    public String getDetailText() {
        return detailText;
    }

    public void setDetailText(String detailText) {
        this.detailText = trimToNull(detailText);
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }
}
