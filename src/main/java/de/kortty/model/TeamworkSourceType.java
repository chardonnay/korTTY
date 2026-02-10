package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum TeamworkSourceType {
    @XmlEnumValue("GIT") GIT,
    @XmlEnumValue("SHARED_FILE") SHARED_FILE
}
