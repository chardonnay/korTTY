package de.kortty.telemetry;

import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.google.common.truth.Truth.assertThat;

class TelemetrySessionTest {

    private static final class SteppingClock extends Clock {
        private Instant now;

        SteppingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void buildsIdFromEpochSecondsPlusEightRandomDigits() {
        assertThat(TelemetrySession.newSessionId(1_700_000_000L, 42)).isEqualTo("170000000000000042");
        assertThat(TelemetrySession.newSessionId(1_700_000_000L, 99_999_999)).isEqualTo("170000000099999999");
        assertThat(TelemetrySession.newSessionId(1_700_000_000L, -7)).matches("1700000000\\d{8}");
    }

    @Test
    void keepsSessionIdWhileActivityContinues() {
        SteppingClock clock = new SteppingClock(Instant.parse("2026-07-04T10:00:00Z"));
        TelemetrySession session = new TelemetrySession(clock);

        String first = session.touchAndGetId();
        clock.advance(Duration.ofMinutes(59));
        assertThat(session.touchAndGetId()).isEqualTo(first);
        clock.advance(Duration.ofMinutes(59));
        assertThat(session.touchAndGetId()).isEqualTo(first);
    }

    @Test
    void rotatesSessionIdAfterOneHourIdle() {
        SteppingClock clock = new SteppingClock(Instant.parse("2026-07-04T10:00:00Z"));
        TelemetrySession session = new TelemetrySession(clock);

        String first = session.touchAndGetId();
        clock.advance(Duration.ofMinutes(61));
        assertThat(session.touchAndGetId()).isNotEqualTo(first);
    }
}
