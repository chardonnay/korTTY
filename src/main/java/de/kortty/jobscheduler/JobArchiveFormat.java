package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum JobArchiveFormat {
    @XmlEnumValue("ZIP")
    ZIP(".zip"),

    @XmlEnumValue("ZIP_PASSWORD")
    ZIP_PASSWORD(".zip"),

    @XmlEnumValue("TAR")
    TAR(".tar"),

    @XmlEnumValue("TAR_BZ2")
    TAR_BZ2(".tar.bz2");

    private final String extension;

    JobArchiveFormat(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }
}
