package de.kortty.core;

public record TerminalRecordingExportOptions(
    TerminalRecordingExportFormat format,
    TerminalRecordingTimeRange timeRange,
    boolean includeColor) {

    public TerminalRecordingExportOptions {
        format = format != null ? format : TerminalRecordingExportFormat.WEBM;
        timeRange = timeRange != null ? timeRange : TerminalRecordingTimeRange.all();
    }

    public static TerminalRecordingExportOptions webmDefaults() {
        return new TerminalRecordingExportOptions(
            TerminalRecordingExportFormat.WEBM,
            TerminalRecordingTimeRange.all(),
            true);
    }
}
