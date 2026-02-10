package de.kortty.teamwork;

import de.kortty.model.ServerConnection;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;

/**
 * Cached connection list for one teamwork source.
 */
@XmlType(propOrder = { "sourceId", "lastCheckedMillis", "versionToken", "connections" })
@XmlAccessorType(XmlAccessType.FIELD)
public class CachedTeamworkSource {

    @XmlElement
    private String sourceId;

    @XmlElement
    private long lastCheckedMillis;

    @XmlElement
    private String versionToken;

    @XmlElementWrapper(name = "connections")
    @XmlElement(name = "connection")
    private List<ServerConnection> connections = new ArrayList<>();

    public CachedTeamworkSource() {
    }

    public CachedTeamworkSource(String sourceId, long lastCheckedMillis, String versionToken, List<ServerConnection> connections) {
        this.sourceId = sourceId;
        this.lastCheckedMillis = lastCheckedMillis;
        this.versionToken = versionToken;
        this.connections = connections != null ? new ArrayList<>(connections) : new ArrayList<>();
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public long getLastCheckedMillis() {
        return lastCheckedMillis;
    }

    public void setLastCheckedMillis(long lastCheckedMillis) {
        this.lastCheckedMillis = lastCheckedMillis;
    }

    public String getVersionToken() {
        return versionToken;
    }

    public void setVersionToken(String versionToken) {
        this.versionToken = versionToken;
    }

    public List<ServerConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<ServerConnection> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }
}
