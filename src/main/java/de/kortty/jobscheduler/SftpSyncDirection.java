package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum SftpSyncDirection {
    @XmlEnumValue("UPLOAD")
    UPLOAD,

    @XmlEnumValue("DOWNLOAD")
    DOWNLOAD
}
