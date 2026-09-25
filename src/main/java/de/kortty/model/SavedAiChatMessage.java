package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * One persisted message within a saved AI chat.
 */
@XmlRootElement(name = "message")
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedAiChatMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @XmlElement
    private String role;

    @XmlElement
    private String content;

    @XmlElement
    private long createdAt;

    @XmlElement
    private String aiProfileId;

    @XmlElement
    private String aiProfileName;

    /** Optional separated chain-of-thought for an assistant reply; display-only, may be null. */
    @XmlElement
    private String reasoning;

    /** Internet tool calls (web search, page reads) behind an assistant reply; display-only. */
    @XmlElementWrapper(name = "webToolCalls")
    @XmlElement(name = "webToolCall")
    private List<SavedAiWebToolCall> webToolCalls;

    public SavedAiChatMessage() {
        this.createdAt = System.currentTimeMillis();
    }

    public SavedAiChatMessage(SavedAiChatMessage source) {
        if (source == null) {
            this.createdAt = System.currentTimeMillis();
            return;
        }
        this.role = source.role;
        this.content = source.content;
        this.createdAt = source.createdAt;
        this.aiProfileId = source.aiProfileId;
        this.aiProfileName = source.aiProfileName;
        this.reasoning = source.reasoning;
        if (source.webToolCalls != null && !source.webToolCalls.isEmpty()) {
            this.webToolCalls = new ArrayList<>();
            for (SavedAiWebToolCall call : source.webToolCalls) {
                this.webToolCalls.add(new SavedAiWebToolCall(call));
            }
        }
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getAiProfileId() {
        return aiProfileId;
    }

    public void setAiProfileId(String aiProfileId) {
        this.aiProfileId = aiProfileId;
    }

    public String getAiProfileName() {
        return aiProfileName;
    }

    public void setAiProfileName(String aiProfileName) {
        this.aiProfileName = aiProfileName;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning != null && !reasoning.isBlank() ? reasoning : null;
    }

    public List<SavedAiWebToolCall> getWebToolCalls() {
        return webToolCalls != null ? webToolCalls : List.of();
    }

    /** Stores the calls; an empty list is kept as {@code null} so chats without web use stay unchanged on disk. */
    public void setWebToolCalls(List<SavedAiWebToolCall> webToolCalls) {
        this.webToolCalls = webToolCalls != null && !webToolCalls.isEmpty() ? new ArrayList<>(webToolCalls) : null;
    }
}
