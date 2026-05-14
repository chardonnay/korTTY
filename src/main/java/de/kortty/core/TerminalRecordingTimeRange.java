package de.kortty.core;

public record TerminalRecordingTimeRange(double startSeconds, double endSeconds) {

    public static TerminalRecordingTimeRange all() {
        return new TerminalRecordingTimeRange(0.0, Double.POSITIVE_INFINITY);
    }

    public static TerminalRecordingTimeRange custom(double startSeconds, double endSeconds) {
        return new TerminalRecordingTimeRange(startSeconds, endSeconds);
    }

    public boolean isAll() {
        return startSeconds <= 0.0 && Double.isInfinite(endSeconds);
    }

    public TerminalRecordingTimeRange normalized(double totalDurationSeconds) {
        double total = Double.isFinite(totalDurationSeconds) ? Math.max(0.0, totalDurationSeconds) : 0.0;
        double start = Double.isFinite(startSeconds) ? Math.max(0.0, startSeconds) : 0.0;
        double end = Double.isFinite(endSeconds) ? endSeconds : total;
        end = Math.min(Math.max(0.0, end), total);
        start = Math.min(start, total);
        return new TerminalRecordingTimeRange(start, end);
    }

    public boolean isValidFor(double totalDurationSeconds) {
        TerminalRecordingTimeRange normalized = normalized(totalDurationSeconds);
        return normalized.endSeconds > normalized.startSeconds;
    }
}
