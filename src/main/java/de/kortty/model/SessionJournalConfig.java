package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Per-connection configuration for the session journal. Deliberately separate from
 * {@link TerminalLogConfig}: the legacy terminal logger stays untouched and independently usable.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalConfig")
public class SessionJournalConfig {

    /** Auto-create a journal on every connect of this connection. */
    @XmlElement
    private boolean enabled = false;

    /**
     * Capture assembled user input lines in addition to server output. Opt-out for users who
     * prefer that only the server echo documents their commands (passwords typed at echo-off
     * prompts then never have an input-side path into the journal at all).
     */
    @XmlElement
    private boolean captureInput = true;

    /** Generate periodic AI summaries for this connection's journals. */
    @XmlElement
    private boolean aiSummariesEnabled = true;

    /** Minutes between AI summary passes; 0 = use the global default from settings. */
    @XmlElement
    private int summaryIntervalMinutes = 0;

    /** Size threshold per capture-log part before rolling to the next part (never deletes). */
    @XmlElement
    private int maxLogSizeMB = 25;

    public SessionJournalConfig() {
    }

    public SessionJournalConfig(SessionJournalConfig other) {
        if (other != null) {
            this.enabled = other.enabled;
            this.captureInput = other.captureInput;
            this.aiSummariesEnabled = other.aiSummariesEnabled;
            this.summaryIntervalMinutes = other.summaryIntervalMinutes;
            this.maxLogSizeMB = other.maxLogSizeMB;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCaptureInput() {
        return captureInput;
    }

    public void setCaptureInput(boolean captureInput) {
        this.captureInput = captureInput;
    }

    public boolean isAiSummariesEnabled() {
        return aiSummariesEnabled;
    }

    public void setAiSummariesEnabled(boolean aiSummariesEnabled) {
        this.aiSummariesEnabled = aiSummariesEnabled;
    }

    public int getSummaryIntervalMinutes() {
        return summaryIntervalMinutes;
    }

    public void setSummaryIntervalMinutes(int summaryIntervalMinutes) {
        this.summaryIntervalMinutes = Math.max(0, summaryIntervalMinutes);
    }

    public int getMaxLogSizeMB() {
        return maxLogSizeMB > 0 ? maxLogSizeMB : 25;
    }

    public void setMaxLogSizeMB(int maxLogSizeMB) {
        this.maxLogSizeMB = maxLogSizeMB;
    }

    public long getMaxLogSizeBytes() {
        return (long) getMaxLogSizeMB() * 1024 * 1024;
    }
}
