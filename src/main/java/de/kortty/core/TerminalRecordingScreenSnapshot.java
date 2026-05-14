package de.kortty.core;

import java.util.List;

public record TerminalRecordingScreenSnapshot(
    String content,
    int columns,
    int rows,
    int pixelWidth,
    int pixelHeight,
    List<TerminalRecordingStyleRun> styleRuns) {

    public TerminalRecordingScreenSnapshot {
        content = content != null ? content : "";
        columns = Math.max(0, columns);
        rows = Math.max(0, rows);
        pixelWidth = Math.max(0, pixelWidth);
        pixelHeight = Math.max(0, pixelHeight);
        styleRuns = styleRuns != null ? List.copyOf(styleRuns) : List.of();
    }

    public static TerminalRecordingScreenSnapshot plain(String content) {
        return new TerminalRecordingScreenSnapshot(content, 0, 0, 0, 0, List.of());
    }

    public boolean hasColorData() {
        return !styleRuns.isEmpty();
    }
}
