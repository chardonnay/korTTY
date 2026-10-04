package de.kortty.core.sftp.transfer;

import org.testng.annotations.Test;

import java.time.Duration;

import static com.google.common.truth.Truth.assertThat;

/** Rate smoothing, remaining time and the stall and edge cases of {@link SpeedMeter}. */
class SpeedMeterTest {

    private static final long MS = 1_000_000L;
    private static final long MB = 1024 * 1024;

    @Test
    void steadyRateConvergesAndGivesTheRemainingTime() {
        SpeedMeter meter = new SpeedMeter();
        long bytes = 0;
        long now = 0;
        meter.update(bytes, now);
        for (int i = 0; i < 50; i++) { // 5 s at 1 MiB/s, a sample every 100 ms
            now += 100 * MS;
            bytes += MB / 10;
            meter.update(bytes, now);
        }

        assertThat(meter.bytesPerSecond()).isWithin(MB * 0.01).of(MB * 1.0);
        Duration eta = meter.eta(bytes + 10 * MB);
        assertThat(eta).isNotNull();
        assertThat(eta.toMillis()).isIn(com.google.common.collect.Range.closed(9_800L, 10_200L));
        assertThat(meter.eta(bytes)).isEqualTo(Duration.ZERO);
        assertThat(meter.isStalled()).isFalse();
    }

    @Test
    void aRateChangeIsFollowedSmoothly() {
        SpeedMeter meter = new SpeedMeter();
        long bytes = 0;
        long now = 0;
        meter.update(bytes, now);
        for (int i = 0; i < 30; i++) {
            now += 100 * MS;
            bytes += MB / 10;
            meter.update(bytes, now);
        }
        double before = meter.bytesPerSecond();
        now += 100 * MS;
        bytes += 4 * MB / 10; // one sample at 4 MiB/s moves the average, but only a bit
        meter.update(bytes, now);

        assertThat(meter.bytesPerSecond()).isGreaterThan(before);
        assertThat(meter.bytesPerSecond()).isLessThan(2.0 * MB);
    }

    @Test
    void aStallGivesNoRemainingTime() {
        SpeedMeter meter = new SpeedMeter();
        long bytes = 0;
        long now = 0;
        meter.update(bytes, now);
        for (int i = 0; i < 20; i++) {
            now += 100 * MS;
            bytes += MB / 10;
            meter.update(bytes, now);
        }
        assertThat(meter.eta(bytes + MB)).isNotNull();

        now += SpeedMeter.DEFAULT_STALL.toNanos();
        meter.update(bytes, now); // no new bytes

        assertThat(meter.isStalled()).isTrue();
        assertThat(meter.bytesPerSecond()).isEqualTo(0.0);
        assertThat(meter.eta(bytes + MB)).isNull();

        now += 100 * MS;
        meter.update(bytes + 1024, now); // data flows again
        assertThat(meter.isStalled()).isFalse();
    }

    @Test
    void noDivisionByZeroAndNoGuessesWithoutARate() {
        SpeedMeter meter = new SpeedMeter();
        assertThat(meter.eta(100)).isNull();
        assertThat(meter.bytesPerSecond()).isEqualTo(0.0);

        meter.update(0, 1_000 * MS);
        meter.update(500, 1_000 * MS); // same instant
        meter.update(900, 1_000 * MS + 1); // one nanosecond later: below the sample spacing
        assertThat(meter.bytesPerSecond()).isEqualTo(0.0);
        assertThat(meter.eta(10_000)).isNull();

        meter.update(800, 900 * MS); // time running backwards restarts the baseline
        assertThat(Double.isFinite(meter.bytesPerSecond())).isTrue();
        assertThat(meter.eta(-1)).isNull(); // unknown size
    }

    @Test
    void aRateBelowTheFloorGivesNoRemainingTime() {
        SpeedMeter meter = new SpeedMeter();
        long now = 0;
        long bytes = 0;
        meter.update(bytes, now);
        for (int i = 0; i < 20; i++) {
            now += 1_000 * MS;
            bytes += 100; // 100 B/s, below the 512 B/s floor
            meter.update(bytes, now);
        }

        assertThat(meter.bytesPerSecond()).isGreaterThan(0.0);
        assertThat(meter.eta(bytes + 1_000_000)).isNull();
    }
}
