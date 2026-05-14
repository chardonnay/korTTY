package de.kortty.core;

import java.util.Arrays;
import java.util.List;

public final class TerminalRecordingReplayTimeline {

    private static final double MIN_FRAME_DURATION_SECONDS = 0.001;

    private final List<TerminalRecordingReplayFrame> frames;
    private final double[] frameStartSeconds;
    private final double totalDurationSeconds;

    public TerminalRecordingReplayTimeline(List<TerminalRecordingReplayFrame> frames) {
        this.frames = frames != null ? List.copyOf(frames) : List.of();
        this.frameStartSeconds = new double[this.frames.size()];

        double cursorSeconds = 0.0;
        for (int i = 0; i < this.frames.size(); i++) {
            frameStartSeconds[i] = cursorSeconds;
            cursorSeconds += durationSeconds(this.frames.get(i));
        }
        this.totalDurationSeconds = cursorSeconds;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public int frameCount() {
        return frames.size();
    }

    public TerminalRecordingReplayFrame frame(int index) {
        if (frames.isEmpty()) {
            throw new IndexOutOfBoundsException("No replay frames available");
        }
        int safeIndex = Math.max(0, Math.min(index, frames.size() - 1));
        return frames.get(safeIndex);
    }

    public double totalDurationSeconds() {
        return totalDurationSeconds;
    }

    public double clampSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0 || totalDurationSeconds <= 0.0) {
            return 0.0;
        }
        return Math.min(seconds, totalDurationSeconds);
    }

    public int frameIndexAt(double seconds) {
        if (frames.isEmpty()) {
            return 0;
        }
        double clampedSeconds = clampSeconds(seconds);
        if (clampedSeconds >= totalDurationSeconds) {
            return frames.size() - 1;
        }
        int exactIndex = Arrays.binarySearch(frameStartSeconds, clampedSeconds);
        if (exactIndex >= 0) {
            return Math.min(exactIndex, frames.size() - 1);
        }
        int insertionPoint = -exactIndex - 1;
        return Math.max(0, insertionPoint - 1);
    }

    public static double durationSeconds(TerminalRecordingReplayFrame frame) {
        double seconds = frame != null ? frame.durationSeconds() : MIN_FRAME_DURATION_SECONDS;
        if (!Double.isFinite(seconds)) {
            return MIN_FRAME_DURATION_SECONDS;
        }
        return Math.max(MIN_FRAME_DURATION_SECONDS, seconds);
    }
}
