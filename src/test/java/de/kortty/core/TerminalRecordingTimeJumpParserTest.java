package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import java.util.OptionalDouble;
import org.testng.annotations.Test;

class TerminalRecordingTimeJumpParserTest {

    @Test
    void parseSecondsAcceptsMinutesAndMinuteSecondInput() {
        assertThat(value(TerminalRecordingTimeJumpParser.parseSeconds("5", 600.0))).isEqualTo(300.0);
        assertThat(value(TerminalRecordingTimeJumpParser.parseSeconds("5:30", 600.0))).isEqualTo(330.0);
        assertThat(value(TerminalRecordingTimeJumpParser.parseSeconds("00:45", 600.0))).isEqualTo(45.0);
        assertThat(value(TerminalRecordingTimeJumpParser.parseSeconds("1,5", 600.0))).isEqualTo(90.0);
    }

    @Test
    void parseSecondsRejectsValuesPastReplayDuration() {
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("10:01", 600.0).isPresent()).isFalse();
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("11", 600.0).isPresent()).isFalse();
    }

    @Test
    void parseSecondsRejectsInvalidTimeValues() {
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("", 600.0).isPresent()).isFalse();
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("-1", 600.0).isPresent()).isFalse();
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("1:60", 600.0).isPresent()).isFalse();
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("1:02:03", 600.0).isPresent()).isFalse();
        assertThat(TerminalRecordingTimeJumpParser.parseSeconds("abc", 600.0).isPresent()).isFalse();
    }

    private static double value(OptionalDouble optional) {
        assertThat(optional.isPresent()).isTrue();
        return optional.getAsDouble();
    }
}
