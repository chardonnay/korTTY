package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * User-defined environment for credentials (e.g. custom names alongside built-in Production, Development, Test, Staging).
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class EnvironmentDefinition {

    @XmlAttribute(required = true)
    private String id;

    @XmlAttribute(name = "displayName", required = true)
    private String displayName;

    public EnvironmentDefinition() {
    }

    public EnvironmentDefinition(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
