package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum JournalDetailMode {
    @XmlEnumValue("LIMITED_REDACTED")
    LIMITED_REDACTED,

    @XmlEnumValue("FULL")
    FULL
}
