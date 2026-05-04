package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.time.Instant;

@XmlRootElement(name = "pinnedHostKey")
@XmlAccessorType(XmlAccessType.FIELD)
public class PinnedHostKey {

    @XmlElement
    private String connectionId;

    @XmlElement
    private String host;

    @XmlElement
    private int port;

    @XmlElement
    private String algorithm;

    @XmlElement
    private String fingerprintSha256;

    @XmlElement
    private String publicKeyLine;

    @XmlElement
    private String pinnedAt = Instant.now().toString();

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = trimToNull(connectionId);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = trimToNull(host);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = trimToNull(algorithm);
    }

    public String getFingerprintSha256() {
        return fingerprintSha256;
    }

    public void setFingerprintSha256(String fingerprintSha256) {
        this.fingerprintSha256 = trimToNull(fingerprintSha256);
    }

    public String getPublicKeyLine() {
        return publicKeyLine;
    }

    public void setPublicKeyLine(String publicKeyLine) {
        this.publicKeyLine = trimToNull(publicKeyLine);
    }

    public String getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(String pinnedAt) {
        this.pinnedAt = trimToNull(pinnedAt);
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }
}
