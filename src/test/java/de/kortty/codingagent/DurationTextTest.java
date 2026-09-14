package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

class DurationTextTest {

    @Test
    void formatsMinutesAndSeconds() {
        assertThat(DurationText.mmss(0)).isEqualTo("0:00");
        assertThat(DurationText.mmss(5)).isEqualTo("0:05");
        assertThat(DurationText.mmss(59)).isEqualTo("0:59");
        assertThat(DurationText.mmss(60)).isEqualTo("1:00");
        assertThat(DurationText.mmss(61)).isEqualTo("1:01");
        assertThat(DurationText.mmss(600)).isEqualTo("10:00");
        assertThat(DurationText.mmss(3599)).isEqualTo("59:59");
    }

    @Test
    void formatsHoursBeyondAnHour() {
        assertThat(DurationText.mmss(3600)).isEqualTo("1:00:00");
        assertThat(DurationText.mmss(3661)).isEqualTo("1:01:01");
        assertThat(DurationText.mmss(36_000)).isEqualTo("10:00:00");
        assertThat(DurationText.mmss(90_061)).isEqualTo("25:01:01");
    }

    @Test
    void negativeValuesRenderAsZero() {
        assertThat(DurationText.mmss(-1)).isEqualTo("0:00");
        assertThat(DurationText.mmss(Long.MIN_VALUE)).isEqualTo("0:00");
    }

    @Test
    void valuesBelowAnHourKeepTheirIdentity() {
        assertThat(DurationText.mmss(5)).isSameInstanceAs(DurationText.mmss(5));
        for (long seconds = 0; seconds < 3600; seconds++) {
            assertThat(DurationText.mmss(seconds)).isSameInstanceAs(DurationText.mmss(seconds));
        }
        assertThat(DurationText.mmss(-7)).isSameInstanceAs(DurationText.mmss(0));
    }
}
