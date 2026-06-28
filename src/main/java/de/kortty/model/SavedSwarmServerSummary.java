package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Persisted final per-server summary of one swarm message. Only the end result is kept (no live
 * transcripts/activity streams).
 */
@XmlRootElement(name = "serverSummary")
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedSwarmServerSummary {

    @XmlElement
    private String serverDisplayName;

    @XmlElement
    private String finalState;

    @XmlElement
    private String summaryText;

    @XmlElement
    private long elapsedSeconds;

    @XmlElement
    private long totalTokens;

    public SavedSwarmServerSummary() {
    }

    public SavedSwarmServerSummary(SavedSwarmServerSummary source) {
        if (source == null) {
            return;
        }
        this.serverDisplayName = source.serverDisplayName;
        this.finalState = source.finalState;
        this.summaryText = source.summaryText;
        this.elapsedSeconds = source.elapsedSeconds;
        this.totalTokens = source.totalTokens;
    }

    public String getServerDisplayName() {
        return serverDisplayName;
    }

    public void setServerDisplayName(String serverDisplayName) {
        this.serverDisplayName = serverDisplayName;
    }

    public String getFinalState() {
        return finalState;
    }

    public void setFinalState(String finalState) {
        this.finalState = finalState;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public long getElapsedSeconds() {
        return elapsedSeconds;
    }

    public void setElapsedSeconds(long elapsedSeconds) {
        this.elapsedSeconds = elapsedSeconds;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }
}
