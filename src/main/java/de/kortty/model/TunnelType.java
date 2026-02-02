package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * SSH tunnel types (port forwarding modes).
 */
@XmlEnum
public enum TunnelType {
    @XmlEnumValue("LOCAL")
    LOCAL,    // Local port forwarding: -L localPort:remoteHost:remotePort
    
    @XmlEnumValue("REMOTE")
    REMOTE,   // Remote port forwarding: -R remotePort:localHost:localPort
    
    @XmlEnumValue("DYNAMIC")
    DYNAMIC   // Dynamic port forwarding (SOCKS): -D localPort
}
