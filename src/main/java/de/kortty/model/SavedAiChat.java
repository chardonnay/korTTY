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

    /** {@link #origin} of a chat seeded from a KI-Agent run; ordinary chats keep it null. */
    public static final String ORIGIN_AGENT_RUN = "AGENT_RUN";

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

    /** Optional text file attached to the chat's first request (name as selected in the terminal). */
    @XmlElement
    private String attachmentFileName;

    @XmlElement
    private String attachmentSourcePath;

    @XmlElement
    private String attachmentContent;

    /** How the chat came to be: {@link #ORIGIN_AGENT_RUN} for a handoff from a KI-Agent run; null otherwise. */
    @XmlElement
    private String origin;

    /** The KI-Agent run this chat was seeded from; null for ordinary chats. */
    @XmlElement
    private String sourceRunId;

    @XmlElement
    private String sourceTerminalSessionId;

    @XmlElement
    private String sourceConnectionId;

    /** Id of the session journal the run was recorded in, when one was active. */
    @XmlElement
    private String sourceJournalId;

    /** Id of the journal's AGENT entry for the run, when one was written. */
    @XmlElement
    private String sourceJournalEntryId;

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
        this.attachmentFileName = source.attachmentFileName;
        this.attachmentSourcePath = source.attachmentSourcePath;
        this.attachmentContent = source.attachmentContent;
        this.origin = source.origin;
        this.sourceRunId = source.sourceRunId;
        this.sourceTerminalSessionId = source.sourceTerminalSessionId;
        this.sourceConnectionId = source.sourceConnectionId;
        this.sourceJournalId = source.sourceJournalId;
        this.sourceJournalEntryId = source.sourceJournalEntryId;
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

    public String getAttachmentFileName() {
        return attachmentFileName;
    }

    public void setAttachmentFileName(String attachmentFileName) {
        this.attachmentFileName = attachmentFileName;
    }

    public String getAttachmentSourcePath() {
        return attachmentSourcePath;
    }

    public void setAttachmentSourcePath(String attachmentSourcePath) {
        this.attachmentSourcePath = attachmentSourcePath;
    }

    public String getAttachmentContent() {
        return attachmentContent;
    }

    public void setAttachmentContent(String attachmentContent) {
        this.attachmentContent = attachmentContent;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    /** True when the chat was seeded from a KI-Agent run. */
    public boolean isAgentRunChat() {
        return ORIGIN_AGENT_RUN.equals(origin);
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(String sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    public String getSourceTerminalSessionId() {
        return sourceTerminalSessionId;
    }

    public void setSourceTerminalSessionId(String sourceTerminalSessionId) {
        this.sourceTerminalSessionId = sourceTerminalSessionId;
    }

    public String getSourceConnectionId() {
        return sourceConnectionId;
    }

    public void setSourceConnectionId(String sourceConnectionId) {
        this.sourceConnectionId = sourceConnectionId;
    }

    public String getSourceJournalId() {
        return sourceJournalId;
    }

    public void setSourceJournalId(String sourceJournalId) {
        this.sourceJournalId = sourceJournalId;
    }

    public String getSourceJournalEntryId() {
        return sourceJournalEntryId;
    }

    public void setSourceJournalEntryId(String sourceJournalEntryId) {
        this.sourceJournalEntryId = sourceJournalEntryId;
    }

    /** True when a file attachment with a name and non-empty content is stored. */
    public boolean hasAttachment() {
        return attachmentFileName != null && !attachmentFileName.isBlank()
            && attachmentContent != null && !attachmentContent.isEmpty();
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
