package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Represents an SSH tunnel configuration (port forwarding).
 */
@XmlRootElement(name = "sshTunnel")
@XmlAccessorType(XmlAccessType.FIELD)
public class SSHTunnel {
    
    @XmlElement
    private boolean enabled = false;
    
    @XmlElement
    private TunnelType type = TunnelType.LOCAL;
    
    @XmlElement
    private String localHost = "localhost";
    
    @XmlElement
    private int localPort;
    
    @XmlElement
    private String remoteHost = "localhost";
    
    @XmlElement
    private int remotePort;
    
    @XmlElement
    private String description;
    
    @XmlEnum
    public enum TunnelType {
        @XmlEnumValue("LOCAL")
        LOCAL,    // Local port forwarding: -L localPort:remoteHost:remotePort
        @XmlEnumValue("REMOTE")
        REMOTE,   // Remote port forwarding: -R remotePort:localHost:localPort
        @XmlEnumValue("DYNAMIC")
        DYNAMIC   // Dynamic port forwarding (SOCKS): -D localPort
    }
    
    public SSHTunnel() {
    }
    
    public SSHTunnel(TunnelType type, int localPort, String remoteHost, int remotePort) {
        this.type = type;
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }
    
    // Getters and Setters
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public TunnelType getType() {
        return type;
    }
    
    public void setType(TunnelType type) {
        this.type = type;
    }
    
    public String getLocalHost() {
        return localHost;
    }
    
    public void setLocalHost(String localHost) {
        this.localHost = localHost;
    }
    
    public int getLocalPort() {
        return localPort;
    }
    
    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }
    
    public String getRemoteHost() {
        return remoteHost;
    }
    
    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }
    
    public int getRemotePort() {
        return remotePort;
    }
    
    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    @Override
    public String toString() {
        return String.format("%s: %s:%d -> %s:%d", type, localHost, localPort, remoteHost, remotePort);
    }
}
