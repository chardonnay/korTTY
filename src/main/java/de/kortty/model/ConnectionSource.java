package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Origin of a connection: local (user's machine) or teamwork (shared source).
 */
@XmlEnum
public enum ConnectionSource {
    @XmlEnumValue("LOCAL") LOCAL,
    @XmlEnumValue("TEAMWORK") TEAMWORK
}
