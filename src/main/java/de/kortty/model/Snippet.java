package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a reusable code snippet for the embedded editor and terminal.
 * Snippets can be tagged, categorized, and contain placeholder variables.
 */
@XmlRootElement(name = "snippet")
@XmlAccessorType(XmlAccessType.FIELD)
public class Snippet {
    
    @XmlElement(required = true)
    private String id;
    
    @XmlElement(required = true)
    private String name;
    
    @XmlElement(required = true)
    private String content;
    
    @XmlElement
    private String language;
    
    @XmlElement
    private String category;

    @XmlElement
    private String operatingSystem;

    @XmlElement
    private String description;
    
    @XmlElementWrapper(name = "tags")
    @XmlElement(name = "tag")
    private List<String> tags = new ArrayList<>();

    @XmlElementWrapper(name = "diagrams")
    @XmlElement(name = "diagram")
    private List<SnippetDiagram> diagrams = new ArrayList<>();
    
    @XmlElement
    private boolean favorite;
    
    @XmlElement
    private int usageCount;
    
    @XmlElement
    private long lastUsed;
    
    @XmlElement
    private long createdAt;

    @XmlElementWrapper(name = "history")
    @XmlElement(name = "entry")
    private List<SnippetHistoryEntry> history = new ArrayList<>();

    @XmlElement
    private Integer historyMaxSize; // null = use global default

    /**
     * True for snippets injected from the enterprise policy (admin script headers). Never
     * persisted — policy snippets are rebuilt from the policy file on every load.
     */
    @XmlTransient
    private boolean policyManaged;

    public Snippet() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
    
    public Snippet(String name, String content, String language) {
        this();
        this.name = name;
        this.content = content;
        this.language = language;
    }
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getOperatingSystem() { return operatingSystem; }
    public void setOperatingSystem(String operatingSystem) { this.operatingSystem = operatingSystem; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public List<SnippetDiagram> getDiagrams() {
        if (diagrams == null) {
            diagrams = new ArrayList<>();
        }
        return diagrams;
    }

    public void setDiagrams(List<SnippetDiagram> diagrams) {
        this.diagrams = diagrams != null ? diagrams : new ArrayList<>();
    }
    
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    
    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
    
    public long getLastUsed() { return lastUsed; }
    public void setLastUsed(long lastUsed) { this.lastUsed = lastUsed; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public List<SnippetHistoryEntry> getHistory() {
        if (history == null) {
            history = new ArrayList<>();
        }
        return history;
    }

    public void setHistory(List<SnippetHistoryEntry> history) {
        this.history = history != null ? history : new ArrayList<>();
    }

    public Integer getHistoryMaxSize() { return historyMaxSize; }

    public void setHistoryMaxSize(Integer historyMaxSize) { this.historyMaxSize = historyMaxSize; }

    public boolean isPolicyManaged() { return policyManaged; }

    public void setPolicyManaged(boolean policyManaged) { this.policyManaged = policyManaged; }

    /**
     * Increments usage count and updates last used timestamp.
     */
    public void touch() {
        this.usageCount++;
        this.lastUsed = System.currentTimeMillis();
    }
    
    /**
     * Returns a comma-separated string of tags.
     */
    public String getTagsAsString() {
        return String.join(", ", tags);
    }
    
    /**
     * Sets tags from a comma-separated string.
     */
    public void setTagsFromString(String tagsString) {
        this.tags = new ArrayList<>();
        if (tagsString != null && !tagsString.isBlank()) {
            for (String tag : tagsString.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    this.tags.add(trimmed);
                }
            }
        }
    }
    
    @Override
    public String toString() {
        return name;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Snippet snippet = (Snippet) o;
        return Objects.equals(id, snippet.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
