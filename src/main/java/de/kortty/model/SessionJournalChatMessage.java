package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One message of the AI chat copy a journal keeps for an AGENT entry. Holds the visible texts and
 * the agent steps only: no reasoning, no web tool calls, no attachment content.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionJournalChatMessage {

    @XmlElement
    private String role;

    @XmlElement
    private String content;

    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime createdAt;

    @XmlElement
    private String aiProfileName;

    @XmlElementWrapper(name = "steps")
    @XmlElement(name = "step")
    private List<SavedAgentStep> steps;

    public SessionJournalChatMessage() {
    }

    public SessionJournalChatMessage(SessionJournalChatMessage other) {
        this.role = other.role;
        this.content = other.content;
        this.createdAt = other.createdAt;
        this.aiProfileName = other.aiProfileName;
        setSteps(other.steps);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAiProfileName() {
        return aiProfileName;
    }

    public void setAiProfileName(String aiProfileName) {
        this.aiProfileName = aiProfileName;
    }

    public List<SavedAgentStep> getSteps() {
        return steps != null ? steps : List.of();
    }

    /** Stores copies of the steps; an empty list is kept as {@code null}. */
    public void setSteps(List<SavedAgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            this.steps = null;
            return;
        }
        List<SavedAgentStep> copies = new ArrayList<>();
        for (SavedAgentStep step : steps) {
            if (step != null) {
                copies.add(new SavedAgentStep(step));
            }
        }
        this.steps = copies.isEmpty() ? null : copies;
    }

    /** Drops an empty steps wrapper before marshalling so a message without steps stays lean. */
    @SuppressWarnings("unused")
    private void beforeMarshal(jakarta.xml.bind.Marshaller marshaller) {
        if (steps != null && steps.isEmpty()) {
            steps = null;
        }
    }
}
