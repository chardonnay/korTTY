package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * One persisted message within a saved swarm chat. Assistant messages carry the per-server final
 * summaries (not live transcripts).
 */
@XmlRootElement(name = "swarmMessage")
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedSwarmMessage {

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

    @XmlElementWrapper(name = "serverSummaries")
    @XmlElement(name = "serverSummary")
    private List<SavedSwarmServerSummary> serverSummaries = new ArrayList<>();

    public SavedSwarmMessage() {
        this.createdAt = System.currentTimeMillis();
    }

    public SavedSwarmMessage(SavedSwarmMessage source) {
        if (source == null) {
            this.createdAt = System.currentTimeMillis();
            return;
        }
        this.role = source.role;
        this.content = source.content;
        this.createdAt = source.createdAt;
        this.aiProfileId = source.aiProfileId;
        this.aiProfileName = source.aiProfileName;
        setServerSummaries(source.serverSummaries);
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

    public List<SavedSwarmServerSummary> getServerSummaries() {
        return serverSummaries;
    }

    public void setServerSummaries(List<SavedSwarmServerSummary> serverSummaries) {
        this.serverSummaries = new ArrayList<>();
        if (serverSummaries == null) {
            return;
        }
        for (SavedSwarmServerSummary summary : serverSummaries) {
            if (summary != null) {
                this.serverSummaries.add(new SavedSwarmServerSummary(summary));
            }
        }
    }
}
