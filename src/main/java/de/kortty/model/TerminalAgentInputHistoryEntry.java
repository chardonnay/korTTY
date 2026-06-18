package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * One remembered terminal AI-agent prompt together with the timestamp of its most recent use.
 * Entries are de-duplicated by {@link #prompt} only — running the same prompt again updates the
 * timestamp instead of creating a second entry.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class TerminalAgentInputHistoryEntry {

    @XmlElement
    private String prompt;

    /** Epoch milliseconds of the most recent execution of this prompt. */
    @XmlElement
    private long lastUsedEpochMillis;

    public TerminalAgentInputHistoryEntry() {
    }

    public TerminalAgentInputHistoryEntry(String prompt, long lastUsedEpochMillis) {
        this.prompt = prompt;
        this.lastUsedEpochMillis = lastUsedEpochMillis;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public long getLastUsedEpochMillis() {
        return lastUsedEpochMillis;
    }

    public void setLastUsedEpochMillis(long lastUsedEpochMillis) {
        this.lastUsedEpochMillis = lastUsedEpochMillis;
    }
}
