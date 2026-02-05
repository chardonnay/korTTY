package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;

/**
 * Represents a category for organizing code snippets.
 */
@XmlRootElement(name = "snippetCategory")
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetCategory {
    
    @XmlElement(required = true)
    private String id;
    
    @XmlElement(required = true)
    private String name;
    
    @XmlElement
    private int sortOrder;
    
    public SnippetCategory() {
        this.id = java.util.UUID.randomUUID().toString();
    }
    
    public SnippetCategory(String name) {
        this();
        this.name = name;
    }
    
    public SnippetCategory(String name, int sortOrder) {
        this(name);
        this.sortOrder = sortOrder;
    }
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
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
        SnippetCategory that = (SnippetCategory) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
