package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum SudoSecretScope {
    @XmlEnumValue("SERVER")
    SERVER,

    @XmlEnumValue("GROUP")
    GROUP
}
