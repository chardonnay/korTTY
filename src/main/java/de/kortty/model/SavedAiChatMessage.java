package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

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
}
