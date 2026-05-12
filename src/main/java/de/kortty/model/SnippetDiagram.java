package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * Persisted PlantUML diagram generated for a snippet.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetDiagram {

    public static final String TYPE_LOGICAL_STRUCTURE = "logical-structure";

    @XmlElement(required = true)
    private String id;

    @XmlElement
    private String title;

    @XmlElement
    private String type = TYPE_LOGICAL_STRUCTURE;

    @XmlElement
    private String plantUmlSource;

    @XmlElement
    private String sourceContentSha256;

    @XmlElement
    private String customInstructions;

    @XmlElement
    private long createdAt;

    @XmlElement
    private long updatedAt;

    public SnippetDiagram() {
        this.id = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public SnippetDiagram(SnippetDiagram source) {
        this.id = source != null && source.id != null ? source.id : java.util.UUID.randomUUID().toString();
        this.title = source != null ? source.title : null;
        this.type = source != null && source.type != null ? source.type : TYPE_LOGICAL_STRUCTURE;
        this.plantUmlSource = source != null ? source.plantUmlSource : null;
        this.sourceContentSha256 = source != null ? source.sourceContentSha256 : null;
        this.customInstructions = source != null ? source.customInstructions : null;
        this.createdAt = source != null ? source.createdAt : System.currentTimeMillis();
        this.updatedAt = source != null ? source.updatedAt : this.createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id : java.util.UUID.randomUUID().toString();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type != null && !type.isBlank() ? type : TYPE_LOGICAL_STRUCTURE;
    }

    public String getPlantUmlSource() {
        return plantUmlSource;
    }

    public void setPlantUmlSource(String plantUmlSource) {
        this.plantUmlSource = plantUmlSource;
    }

    public String getSourceContentSha256() {
        return sourceContentSha256;
    }

    public void setSourceContentSha256(String sourceContentSha256) {
        this.sourceContentSha256 = sourceContentSha256;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }

    public void setCustomInstructions(String customInstructions) {
        this.customInstructions = customInstructions;
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

    @Override
    public String toString() {
        return title != null && !title.isBlank() ? title : "Snippet diagram";
    }
}
