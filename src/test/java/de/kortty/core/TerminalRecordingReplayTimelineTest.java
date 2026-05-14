package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.testng.annotations.Test;

class TerminalRecordingReplayTimelineTest {

    @Test
    void frameIndexAtUsesRecordedDurations() {
        TerminalRecordingReplayTimeline timeline = new TerminalRecordingReplayTimeline(List.of(
            new TerminalRecordingReplayFrame("one", 1.0),
            new TerminalRecordingReplayFrame("two", 2.0),
            new TerminalRecordingReplayFrame("three", 3.0)));

        assertThat(timeline.frameIndexAt(0.0)).isEqualTo(0);
        assertThat(timeline.frameIndexAt(0.999)).isEqualTo(0);
        assertThat(timeline.frameIndexAt(1.0)).isEqualTo(1);
        assertThat(timeline.frameIndexAt(2.999)).isEqualTo(1);
        assertThat(timeline.frameIndexAt(3.0)).isEqualTo(2);
        assertThat(timeline.frameIndexAt(6.0)).isEqualTo(2);
    }

    @Test
    void totalDurationUsesMinimumDurationForInvalidFrameDurations() {
        TerminalRecordingReplayTimeline timeline = new TerminalRecordingReplayTimeline(List.of(
            new TerminalRecordingReplayFrame("zero", 0.0),
            new TerminalRecordingReplayFrame("nan", Double.NaN),
            new TerminalRecordingReplayFrame("valid", 2.0)));

        assertThat(timeline.totalDurationSeconds()).isWithin(0.0001).of(2.002);
        assertThat(timeline.frameIndexAt(0.001)).isEqualTo(1);
        assertThat(timeline.frameIndexAt(0.002)).isEqualTo(2);
    }

    @Test
    void clampSecondsLimitsToRecordingDuration() {
        TerminalRecordingReplayTimeline timeline = new TerminalRecordingReplayTimeline(List.of(
            new TerminalRecordingReplayFrame("one", 1.0),
            new TerminalRecordingReplayFrame("two", 2.0)));

        assertThat(timeline.clampSeconds(-1.0)).isEqualTo(0.0);
        assertThat(timeline.clampSeconds(0.0)).isEqualTo(0.0);
        assertThat(timeline.clampSeconds(Double.NaN)).isEqualTo(0.0);
        assertThat(timeline.clampSeconds(1.5)).isEqualTo(1.5);
        assertThat(timeline.clampSeconds(10.0)).isEqualTo(3.0);
    }
}
