package de.kortty.core;

import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AutomationScreenshotSchedulerTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T10:00:00Z");

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

    private ManualClock clock;
    private long changes;
    private String content;
    private List<Boolean> shots;

    @org.testng.annotations.BeforeMethod
    void reset() {
        clock = new ManualClock();
        changes = 0;
        content = "";
        shots = new ArrayList<>();
    }

    private AutomationScreenshotScheduler scheduler(Duration interval, boolean onChange, int max) {
        return new AutomationScreenshotScheduler(clock, interval, onChange, Duration.ofSeconds(2), max,
            () -> new AutomationScreenshotScheduler.Screen() {
                @Override
                public long changeCount() {
                    return changes;
                }

                @Override
                public String contentKey() {
                    return content;
                }
            },
            shots::add);
    }

    private void screen(String text) {
        content = text;
        changes++;
    }

    @Test
    void takesPeriodicScreenshotsAndSkipsUnchangedScreens() {
        AutomationScreenshotScheduler scheduler = scheduler(Duration.ofSeconds(10), false, 30);
        screen("frame 1");
        clock.advance(Duration.ofSeconds(5));
        scheduler.tick();
        assertThat(shots).isEmpty();

        clock.advance(Duration.ofSeconds(5));
        scheduler.tick();
        assertThat(shots).containsExactly(false);

        clock.advance(Duration.ofSeconds(10));
        scheduler.tick(); // same screen: no duplicate
        assertThat(shots).hasSize(1);

        screen("frame 2");
        clock.advance(Duration.ofSeconds(10));
        scheduler.tick();
        assertThat(shots).hasSize(2);
    }

    @Test
    void takesAScreenshotOnChangeButNotMoreOftenThanTheGap() {
        AutomationScreenshotScheduler scheduler = scheduler(Duration.ZERO, true, 30);
        clock.advance(Duration.ofSeconds(3));
        screen("a");
        scheduler.tick();
        assertThat(shots).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        screen("b");
        scheduler.tick(); // within the 2 s gap
        assertThat(shots).hasSize(1);

        clock.advance(Duration.ofSeconds(2));
        scheduler.tick(); // change still pending? the counter moved before; a new change is needed
        screen("c");
        clock.advance(Duration.ofSeconds(1));
        scheduler.tick();
        assertThat(shots).hasSize(2);
    }

    @Test
    void keepsTheLastSlotForTheFinalScreen() {
        AutomationScreenshotScheduler scheduler = scheduler(Duration.ofSeconds(1), false, 3);
        for (int i = 0; i < 10; i++) {
            screen("frame " + i);
            clock.advance(Duration.ofSeconds(1));
            scheduler.tick();
        }
        assertThat(shots).hasSize(2);

        screen("the end");
        scheduler.finish();

        assertThat(shots).containsExactly(false, false, true).inOrder();
        assertThat(scheduler.shotsTaken()).isEqualTo(3);
    }

    @Test
    void finalScreenIsSkippedWhenIdenticalOrEmpty() {
        AutomationScreenshotScheduler empty = scheduler(Duration.ZERO, false, 5);
        content = "   ";
        empty.finish();
        assertThat(shots).isEmpty();

        AutomationScreenshotScheduler same = scheduler(Duration.ofSeconds(1), false, 5);
        screen("done");
        clock.advance(Duration.ofSeconds(1));
        same.tick();
        same.finish();
        assertThat(shots).containsExactly(false);
        assertThat(same.finalOnly()).isFalse();
        assertThat(scheduler(Duration.ZERO, false, 5).finalOnly()).isTrue();
    }
}
