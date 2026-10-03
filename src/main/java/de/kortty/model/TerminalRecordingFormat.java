package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Stored recording format from {@code global-settings.xml}. The recorder always writes
 * {@link #KORTTY_REPLAY} files; video output is a separate export step whose format is chosen per
 * export ({@link de.kortty.core.TerminalRecordingExportFormat}).
 */
@XmlEnum
public enum TerminalRecordingFormat {
    @XmlEnumValue("KORTTY_REPLAY")
    KORTTY_REPLAY("KorTTY Replay", "korttyrec.jsonl.gz"),

    /**
     * Never written by the recorder and no longer offered in the Video Manager. Kept only so a
     * {@code global-settings.xml} that stored it still loads.
     *
     * @deprecated not a recording format; video export uses
     *     {@link de.kortty.core.TerminalRecordingExportFormat#WEBM}
     */
    @Deprecated
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
