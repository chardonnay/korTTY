package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The copy of a saved AI chat that a session journal keeps next to its AGENT entry. It is rewritten
 * while the journal records and frozen when the journal stops, so the journal still shows the
 * conversation after the chat was deleted. Masked like every other journal text.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionJournalChatSnapshot {

    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime capturedAt;

    @XmlElement
    private String title;

    /** Null unless the journal has stopped and the copy will not change any more. */
    @XmlElement
    private Boolean frozen;

    @XmlElementWrapper(name = "messages")
    @XmlElement(name = "message")
    private List<SessionJournalChatMessage> messages;

    public SessionJournalChatSnapshot() {
    }

    public SessionJournalChatSnapshot(SessionJournalChatSnapshot other) {
        this.capturedAt = other.capturedAt;
        this.title = other.title;
        this.frozen = other.frozen;
        setMessages(other.messages);
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(OffsetDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isFrozen() {
        return Boolean.TRUE.equals(frozen);
    }

    /** Stores {@code true} only; {@code false} is kept as absent. */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen ? Boolean.TRUE : null;
    }

    /** Live list of the copied messages (copies, not the chat's own). */
    public List<SessionJournalChatMessage> getMessages() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        return messages;
    }

    /** Stores copies of the messages. */
    public void setMessages(List<SessionJournalChatMessage> messages) {
        if (messages == null) {
            this.messages = null;
            return;
        }
        List<SessionJournalChatMessage> copies = new ArrayList<>();
        for (SessionJournalChatMessage message : messages) {
            if (message != null) {
                copies.add(new SessionJournalChatMessage(message));
            }
        }
        this.messages = copies;
    }

    /** The messages as chat messages for the shared transcript rendering; fresh copies, never the stored objects. */
    public List<SavedAiChatMessage> toSavedMessages() {
        List<SavedAiChatMessage> result = new ArrayList<>();
        for (SessionJournalChatMessage message : getMessages()) {
            SavedAiChatMessage copy = new SavedAiChatMessage();
            copy.setRole(message.getRole());
            copy.setContent(message.getContent());
            copy.setCreatedAt(message.getCreatedAt() != null
                ? message.getCreatedAt().toInstant().toEpochMilli()
                : System.currentTimeMillis());
            copy.setAiProfileName(message.getAiProfileName());
            copy.setAgentSteps(message.getSteps());
            result.add(copy);
        }
        return result;
    }

    /** Converts an epoch-millisecond timestamp to the journal's offset date-time. */
    public static OffsetDateTime toOffsetDateTime(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    /** Drops an empty messages wrapper before marshalling. */
    @SuppressWarnings("unused")
    private void beforeMarshal(jakarta.xml.bind.Marshaller marshaller) {
        if (messages != null && messages.isEmpty()) {
            messages = null;
        }
    }
}
