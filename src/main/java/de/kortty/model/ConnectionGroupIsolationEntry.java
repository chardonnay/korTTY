package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * One folder's isolation level as stored in the global settings: the folder's path and the
 * {@link de.kortty.isolation.IsolationLevel#id()}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class ConnectionGroupIsolationEntry {

    @XmlAttribute(required = true)
    private String path;

    @XmlAttribute(required = true)
    private String level;

    public ConnectionGroupIsolationEntry() {
    }

    public ConnectionGroupIsolationEntry(String path, String level) {
        this.path = path;
        this.level = level;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
