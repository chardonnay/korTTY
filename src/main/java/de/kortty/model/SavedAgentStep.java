package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * One step of a KI-Agent run as kept with a chat message or a journal chat snapshot. Display-only
 * and already masked and capped when it is built; command steps carry the command, the exit code
 * and a bounded output. Thinking steps are never stored.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedAgentStep {

    /** The activity type name: {@code ACTION}, {@code MESSAGE}, {@code QUESTION} or {@code ERROR}. */
    @XmlElement
    private String type;

    /** The activity status name, or null when the run did not report one. */
    @XmlElement
    private String status;

    @XmlElement
    private String title;

    @XmlElement
    private String summary;

    /** The shell command a command step ran; null for other steps. */
    @XmlElement
    private String command;

    /** The command's exit code; null when unknown or not a command step. */
    @XmlElement
    private Integer exitCode;

    /** The command's output (stdout then stderr), head and tail kept when long; null when none. */
    @XmlElement
    private String output;

    @XmlElement
    private long elapsedSeconds;

    /** Null unless the output was cut to fit; kept null for a complete output so the XML stays lean. */
    @XmlElement
    private Boolean truncated;

    public SavedAgentStep() {
    }

    public SavedAgentStep(SavedAgentStep source) {
        if (source == null) {
            return;
        }
        this.type = source.type;
        this.status = source.status;
        this.title = source.title;
        this.summary = source.summary;
        this.command = source.command;
        this.exitCode = source.exitCode;
        this.output = source.output;
        this.elapsedSeconds = source.elapsedSeconds;
        this.truncated = source.truncated;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public long getElapsedSeconds() {
        return elapsedSeconds;
    }

    public void setElapsedSeconds(long elapsedSeconds) {
        this.elapsedSeconds = Math.max(0L, elapsedSeconds);
    }

    public boolean isTruncated() {
        return Boolean.TRUE.equals(truncated);
    }

    /** Stores {@code true} only; {@code false} is kept as absent. */
    public void setTruncated(boolean truncated) {
        this.truncated = truncated ? Boolean.TRUE : null;
    }
}
