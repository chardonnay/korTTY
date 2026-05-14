package de.kortty.core;

public enum TerminalRecordingExportFormat {
    WEBM("WebM/VP9", "webm"),
    MKV("MKV/FFV1", "mkv");

    private final String displayName;
    private final String extension;

    TerminalRecordingExportFormat(String displayName, String extension) {
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
