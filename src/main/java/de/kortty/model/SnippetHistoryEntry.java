package de.kortty.model;

import java.time.Instant;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * Represents a single history entry for a snippet's content changes.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetHistoryEntry {

    @XmlElement
    private String content;

    @XmlElement
    private long timestamp;

    public SnippetHistoryEntry() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    public SnippetHistoryEntry(String content) {
        this.content = content;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public SnippetHistoryEntry(String content, long timestamp) {
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Instant getInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}
