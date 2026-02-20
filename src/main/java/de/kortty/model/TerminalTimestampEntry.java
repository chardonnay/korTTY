package de.kortty.model;

import de.kortty.persistence.LocalDateTimeAdapter;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.LocalDateTime;

/**
 * Serializable terminal timestamp entry mapped to an absolute terminal line.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class TerminalTimestampEntry {

    @XmlElement
    private int absoluteLine;

    @XmlElement
    @XmlJavaTypeAdapter(LocalDateTimeAdapter.class)
    private LocalDateTime timestamp;

    public TerminalTimestampEntry() {
    }

    public TerminalTimestampEntry(int absoluteLine, LocalDateTime timestamp) {
        this.absoluteLine = absoluteLine;
        this.timestamp = timestamp;
    }

    public int getAbsoluteLine() {
        return absoluteLine;
    }

    public void setAbsoluteLine(int absoluteLine) {
        this.absoluteLine = absoluteLine;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
