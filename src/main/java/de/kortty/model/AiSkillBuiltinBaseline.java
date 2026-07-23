package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the delivered version a built-in AI skill is based on. Stored alongside the
 * user-editable copy so modifications can be detected and reverted even after the bundled
 * delivery has moved on to a newer version.
 */
@XmlRootElement(name = "aiSkillBuiltinBaseline")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkillBuiltinBaseline {

    @XmlElement
    private String name;

    @XmlElement
    private String description;

    @XmlElementWrapper(name = "tags")
    @XmlElement(name = "tag")
    private List<String> tags = new ArrayList<>();

    @XmlElement
    private AiSkillTarget target = AiSkill.DEFAULT_TARGET;

    @XmlElement
    private String content;

    /** Shipped {@code kortty-builtin-version} this baseline was taken from. */
    @XmlElement
    private int version = 1;

    public AiSkillBuiltinBaseline() {
    }

    public AiSkillBuiltinBaseline(AiSkillBuiltinBaseline source) {
        if (source == null) {
            return;
        }
        this.name = source.getName();
        this.description = source.getDescription();
        setTags(source.getTags());
        setTarget(source.getTarget());
        this.content = source.getContent();
        setVersion(source.getVersion());
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
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public AiSkillTarget getTarget() {
        return target != null ? target : AiSkill.DEFAULT_TARGET;
    }

    public void setTarget(AiSkillTarget target) {
        this.target = target != null ? target : AiSkill.DEFAULT_TARGET;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = Math.max(1, version);
    }
}
