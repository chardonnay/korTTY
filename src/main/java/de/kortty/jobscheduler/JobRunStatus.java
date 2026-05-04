package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum JobRunStatus {
    @XmlEnumValue("RUNNING")
    RUNNING,

    @XmlEnumValue("SUCCESS")
    SUCCESS,

    @XmlEnumValue("FAILED")
    FAILED,

    @XmlEnumValue("BLOCKED")
    BLOCKED,

    @XmlEnumValue("CANCELLED")
    CANCELLED
}
