package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persisted AI chat that can be reopened and continued later.
 */
@XmlRootElement(name = "chat")
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedAiChat {

    @XmlElement
    private String id;

    @XmlElement
    private String title;

    @XmlElement
    private long createdAt;

    @XmlElement
    private long updatedAt;

    @XmlElement
    private String selectedText;

    @XmlElement
    private String connectionDisplayName;

    @XmlElement
    private String responseLanguageCode;

    @XmlElement
    private String activeAiProfileId;

    @XmlElement
    private String activeAiProfileName;

    @XmlElementWrapper(name = "messages")
    @XmlElement(name = "message")
    private List<SavedAiChatMessage> messages = new ArrayList<>();

    public SavedAiChat() {
        long now = System.currentTimeMillis();
        this.id = UUID.randomUUID().toString();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public SavedAiChat(SavedAiChat source) {
        if (source == null) {
            long now = System.currentTimeMillis();
            this.id = UUID.randomUUID().toString();
            this.createdAt = now;
            this.updatedAt = now;
            return;
        }
        this.id = source.id;
        this.title = source.title;
        this.createdAt = source.createdAt;
        this.updatedAt = source.updatedAt;
        this.selectedText = source.selectedText;
        this.connectionDisplayName = source.connectionDisplayName;
        this.responseLanguageCode = source.responseLanguageCode;
        this.activeAiProfileId = source.activeAiProfileId;
        this.activeAiProfileName = source.activeAiProfileName;
        setMessages(source.messages);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    public String getConnectionDisplayName() {
        return connectionDisplayName;
    }

    public void setConnectionDisplayName(String connectionDisplayName) {
        this.connectionDisplayName = connectionDisplayName;
    }

    public String getResponseLanguageCode() {
        return responseLanguageCode;
    }

    public void setResponseLanguageCode(String responseLanguageCode) {
        this.responseLanguageCode = responseLanguageCode;
    }

    public String getActiveAiProfileId() {
        return activeAiProfileId;
    }

    public void setActiveAiProfileId(String activeAiProfileId) {
        this.activeAiProfileId = activeAiProfileId;
    }

    public String getActiveAiProfileName() {
        return activeAiProfileName;
    }

    public void setActiveAiProfileName(String activeAiProfileName) {
        this.activeAiProfileName = activeAiProfileName;
    }

    public List<SavedAiChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<SavedAiChatMessage> messages) {
        this.messages = new ArrayList<>();
        if (messages == null) {
            return;
        }
        for (SavedAiChatMessage message : messages) {
            if (message != null) {
                this.messages.add(new SavedAiChatMessage(message));
            }
        }
    }
}
