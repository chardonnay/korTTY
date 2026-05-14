package de.kortty.core;

import java.util.List;

public record TerminalRecordingReplayFrame(
    TerminalRecordingScreenSnapshot snapshot,
    double durationSeconds) {

    public TerminalRecordingReplayFrame {
        snapshot = snapshot != null ? snapshot : TerminalRecordingScreenSnapshot.plain("");
    }

    public TerminalRecordingReplayFrame(String content, double durationSeconds) {
        this(TerminalRecordingScreenSnapshot.plain(content), durationSeconds);
    }

    public String content() {
        return snapshot.content();
    }

    public int columns() {
        return snapshot.columns();
    }

    public int rows() {
        return snapshot.rows();
    }

    public int pixelWidth() {
        return snapshot.pixelWidth();
    }

    public int pixelHeight() {
        return snapshot.pixelHeight();
    }

    public List<TerminalRecordingStyleRun> styleRuns() {
        return snapshot.styleRuns();
    }

    public boolean hasColorData() {
        return snapshot.hasColorData();
    }
}
