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
 * Persisted swarm chat that can be reopened and continued later. Stores the chosen target set
 * (connection ids and/or group paths), the profile/language, and the conversation history with
 * per-server final summaries.
 */
@XmlRootElement(name = "swarmChat")
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedSwarmChat {

    @XmlElement
    private String id;

    @XmlElement
    private String title;

    @XmlElement
    private long createdAt;

    @XmlElement
    private long updatedAt;

    @XmlElement
    private String responseLanguageCode;

    @XmlElement
    private String activeAiProfileId;

    @XmlElement
    private String activeAiProfileName;

    @XmlElementWrapper(name = "targetConnectionIds")
    @XmlElement(name = "connectionId")
    private List<String> targetConnectionIds = new ArrayList<>();

    @XmlElementWrapper(name = "targetGroupPaths")
    @XmlElement(name = "groupPath")
    private List<String> targetGroupPaths = new ArrayList<>();

    @XmlElementWrapper(name = "messages")
    @XmlElement(name = "swarmMessage")
    private List<SavedSwarmMessage> messages = new ArrayList<>();

    public SavedSwarmChat() {
        long now = System.currentTimeMillis();
        this.id = UUID.randomUUID().toString();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public SavedSwarmChat(SavedSwarmChat source) {
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
        this.responseLanguageCode = source.responseLanguageCode;
        this.activeAiProfileId = source.activeAiProfileId;
        this.activeAiProfileName = source.activeAiProfileName;
        setTargetConnectionIds(source.targetConnectionIds);
        setTargetGroupPaths(source.targetGroupPaths);
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

    public List<String> getTargetConnectionIds() {
        return targetConnectionIds;
    }

    public void setTargetConnectionIds(List<String> targetConnectionIds) {
        this.targetConnectionIds = new ArrayList<>();
        if (targetConnectionIds != null) {
            for (String id : targetConnectionIds) {
                if (id != null && !id.isBlank()) {
                    this.targetConnectionIds.add(id);
                }
            }
        }
    }

    public List<String> getTargetGroupPaths() {
        return targetGroupPaths;
    }

    public void setTargetGroupPaths(List<String> targetGroupPaths) {
        this.targetGroupPaths = new ArrayList<>();
        if (targetGroupPaths != null) {
            for (String path : targetGroupPaths) {
                if (path != null && !path.isBlank()) {
                    this.targetGroupPaths.add(path);
                }
            }
        }
    }

    public List<SavedSwarmMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<SavedSwarmMessage> messages) {
        this.messages = new ArrayList<>();
        if (messages == null) {
            return;
        }
        for (SavedSwarmMessage message : messages) {
            if (message != null) {
                this.messages.add(new SavedSwarmMessage(message));
            }
        }
    }
}
