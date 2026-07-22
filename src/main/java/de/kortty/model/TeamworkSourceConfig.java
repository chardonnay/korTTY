package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;

import java.util.Objects;
import java.util.UUID;

/**
 * Configuration for one teamwork connection source (Git repo or shared file).
 */
@XmlType(propOrder = { "id", "type", "location", "checkIntervalMinutes", "readOnly", "enabled" })
@XmlAccessorType(XmlAccessType.FIELD)
public class TeamworkSourceConfig {

    @XmlElement
    private String id;

    @XmlElement
    private TeamworkSourceType type = TeamworkSourceType.GIT;

    /** Git clone URL or path to shared file (e.g. network path). */
    @XmlElement
    private String location;

    /** How often to check for updates (minutes). */
    @XmlElement
    private int checkIntervalMinutes = 15;

    /** If true, user cannot push changes (Git) or write to file. */
    @XmlElement
    private boolean readOnly = false;

    @XmlElement
    private boolean enabled = true;

    /**
     * True for sources injected from the enterprise policy. Never persisted — policy sources are
     * rebuilt from the policy file on every settings load.
     */
    @XmlTransient
    private boolean policyManaged;

    public TeamworkSourceConfig() {
        this.id = UUID.randomUUID().toString();
    }

    public TeamworkSourceConfig(TeamworkSourceType type, String location) {
        this();
        this.type = type;
        this.location = location;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TeamworkSourceType getType() {
        return type;
    }

    public void setType(TeamworkSourceType type) {
        this.type = type;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public void setCheckIntervalMinutes(int checkIntervalMinutes) {
        this.checkIntervalMinutes = checkIntervalMinutes;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isPolicyManaged() {
        return policyManaged;
    }

    public void setPolicyManaged(boolean policyManaged) {
        this.policyManaged = policyManaged;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamworkSourceConfig that = (TeamworkSourceConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
