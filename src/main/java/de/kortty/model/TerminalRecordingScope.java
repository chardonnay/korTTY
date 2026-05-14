package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

@XmlEnum
public enum TerminalRecordingScope {
    @XmlEnumValue("ACTIVE_SPLIT")
    ACTIVE_SPLIT("Active split"),

    @XmlEnumValue("WHOLE_TAB")
    WHOLE_TAB("Whole tab");

    private final String displayName;

    TerminalRecordingScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
