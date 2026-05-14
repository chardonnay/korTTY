package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum TerminalRecordingFormat {
    @XmlEnumValue("KORTTY_REPLAY")
    KORTTY_REPLAY("KorTTY Replay", "korttyrec.jsonl.gz"),

    @XmlEnumValue("WEBM")
    WEBM("WebM Video", "webm");

    private final String displayName;
    private final String extension;

    TerminalRecordingFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
