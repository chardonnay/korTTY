package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;

/**
 * A folder in the snippet library. Folders nest through {@link #getParentId() parentId}
 * ({@code null} = top level) and are stored flat next to the snippets, like {@link SnippetCategory}.
 * A folder's name doubles as a directory name when a folder is exported or copied to a server.
 */
@XmlRootElement(name = "snippetFolder")
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetFolder {

    @XmlElement(required = true)
    private String id;

    @XmlElement(required = true)
    private String name;

    @XmlElement
    private String parentId;

    @XmlElement
    private int sortOrder;

    public SnippetFolder() {
        this.id = java.util.UUID.randomUUID().toString();
    }

    public SnippetFolder(String name, String parentId) {
        this();
        this.name = name;
        this.parentId = parentId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SnippetFolder that = (SnippetFolder) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
