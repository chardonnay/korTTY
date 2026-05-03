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
 * User-defined instructions that can be attached to AI prompts.
 */
@XmlRootElement(name = "aiSkill")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkill {

    public static final AiSkillTarget DEFAULT_TARGET = AiSkillTarget.BOTH;

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private String description;

    @XmlElementWrapper(name = "tags")
    @XmlElement(name = "tag")
    private List<String> tags = new ArrayList<>();

    @XmlElement
    private boolean enabled = true;

    @XmlElement
    private AiSkillTarget target = AiSkillTarget.BOTH;

    @XmlElement
    private String content;

    public AiSkill() {
        this.id = UUID.randomUUID().toString();
    }

    public AiSkill(AiSkill source) {
        if (source == null) {
            this.id = UUID.randomUUID().toString();
            return;
        }
        setId(source.getId());
        this.name = source.getName();
        this.description = source.getDescription();
        setTags(source.getTags());
        this.enabled = source.isEnabled();
        setTarget(source.getTarget());
        this.content = source.getContent();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public void ensureId() {
        setId(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTags() {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        normalizeTags();
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        normalizeTags();
    }

    public String getTagsAsString() {
        return String.join(", ", getTags());
    }

    public void setTagsFromString(String tagsString) {
        List<String> parsed = new ArrayList<>();
        if (tagsString != null && !tagsString.isBlank()) {
            for (String tag : tagsString.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isBlank()) {
                    parsed.add(trimmed);
                }
            }
        }
        setTags(parsed);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AiSkillTarget getTarget() {
        return target != null ? target : DEFAULT_TARGET;
    }

    public void setTarget(AiSkillTarget target) {
        this.target = target != null ? target : DEFAULT_TARGET;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    private void normalizeTags() {
        List<String> normalized = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                String trimmed = tag != null ? tag.trim() : "";
                if (!trimmed.isBlank() && normalized.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
                    normalized.add(trimmed);
                }
            }
        }
        tags = normalized;
    }
}
