package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@XmlRootElement(name = "job")
@XmlAccessorType(XmlAccessType.FIELD)
public class ScheduledJob {

    @XmlElement
    private String id = UUID.randomUUID().toString();

    @XmlElement
    private String name = "New Job";

    @XmlElement
    private boolean enabled = true;

    @XmlElement
    private boolean hostKeyVerificationDisabled;

    @XmlElement
    private String connectionId;

    @XmlElement
    private String connectionDisplayName;

    @XmlElementWrapper(name = "targetConnectionIds")
    @XmlElement(name = "connectionId")
    private List<String> targetConnectionIds = new ArrayList<>();

    @XmlElementWrapper(name = "targetGroupNames")
    @XmlElement(name = "groupName")
    private List<String> targetGroupNames = new ArrayList<>();

    @XmlElement
    private String workingDirectory;

    @XmlElement
    private JournalDetailMode journalDetailMode = JournalDetailMode.LIMITED_REDACTED;

    @XmlElement
    private JobSchedule schedule = new JobSchedule();

    @XmlElement
    private JobAction action = new JobAction();

    @XmlElement
    private String lastRunAt;

    @XmlElement
    private String nextRunAt;

    @XmlElement
    private String createdAt = Instant.now().toString();

    @XmlElement
    private String updatedAt = Instant.now().toString();

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public String getName() {
        return name != null && !name.isBlank() ? name : "New Job";
    }

    public void setName(String name) {
        this.name = name != null && !name.isBlank() ? name.trim() : "New Job";
        touch();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        touch();
    }

    public boolean isHostKeyVerificationDisabled() {
        return hostKeyVerificationDisabled;
    }

    public void setHostKeyVerificationDisabled(boolean hostKeyVerificationDisabled) {
        this.hostKeyVerificationDisabled = hostKeyVerificationDisabled;
        touch();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = trimToNull(connectionId);
        touch();
    }

    public String getConnectionDisplayName() {
        return connectionDisplayName;
    }

    public void setConnectionDisplayName(String connectionDisplayName) {
        this.connectionDisplayName = trimToNull(connectionDisplayName);
        touch();
    }

    public List<String> getTargetConnectionIds() {
        List<String> normalized = normalizeList(targetConnectionIds);
        if (normalized.isEmpty() && connectionId != null && !connectionId.isBlank()) {
            return List.of(connectionId);
        }
        return normalized;
    }

    public void setTargetConnectionIds(Collection<String> targetConnectionIds) {
        this.targetConnectionIds = normalizeList(targetConnectionIds);
        touch();
    }

    public List<String> getTargetGroupNames() {
        return normalizeList(targetGroupNames);
    }

    public void setTargetGroupNames(Collection<String> targetGroupNames) {
        this.targetGroupNames = normalizeList(targetGroupNames);
        touch();
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = trimToNull(workingDirectory);
        touch();
    }

    public JournalDetailMode getJournalDetailMode() {
        return journalDetailMode != null ? journalDetailMode : JournalDetailMode.LIMITED_REDACTED;
    }

    public void setJournalDetailMode(JournalDetailMode journalDetailMode) {
        this.journalDetailMode = journalDetailMode != null ? journalDetailMode : JournalDetailMode.LIMITED_REDACTED;
        touch();
    }

    public JobSchedule getSchedule() {
        if (schedule == null) {
            schedule = new JobSchedule();
        }
        return schedule;
    }

    public void setSchedule(JobSchedule schedule) {
        this.schedule = schedule != null ? schedule : new JobSchedule();
        touch();
    }

    public JobAction getAction() {
        if (action == null) {
            action = new JobAction();
        }
        return action;
    }

    public void setAction(JobAction action) {
        this.action = action != null ? action : new JobAction();
        touch();
    }

    public String getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(String lastRunAt) {
        this.lastRunAt = trimToNull(lastRunAt);
    }

    public String getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(String nextRunAt) {
        this.nextRunAt = trimToNull(nextRunAt);
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = trimToNull(createdAt);
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = trimToNull(updatedAt);
    }

    public void touch() {
        updatedAt = Instant.now().toString();
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> normalizeList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }
}
