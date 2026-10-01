package de.kortty.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Decides when a job running in the invisible virtual terminal gets a screenshot: every
 * {@code interval}, whenever the screen changed (at most every {@code minGap}), and once at the
 * end — never twice for the same screen and never more than {@code maxShots} per command (the
 * last slot is kept free for the final screen). The caller ticks it periodically; it holds no
 * thread of its own, which keeps it deterministic under test.
 */
public final class AutomationScreenshotScheduler {

    /** What the scheduler needs to know about the screen at a tick. */
    public interface Screen {
        /** A value that changes whenever the screen changes (e.g. a change counter). */
        long changeCount();

        /** The screen's content, to skip identical screenshots. */
        String contentKey();
    }

    /** Receives each screenshot to take; {@code finalShot} marks the one at the end. */
    @FunctionalInterface
    public interface Sink {
        void take(boolean finalShot);
    }

    private final Clock clock;
    private final Duration interval;
    private final boolean onChange;
    private final Duration minGap;
    private final int maxShots;
    private final Supplier<Screen> screen;
    private final Sink sink;

    private Instant lastShotAt;
    private long lastSeenChangeCount = -1;
    private String lastShotKey;
    private int shots;
    private boolean finished;

    /**
     * @param interval  time between periodic screenshots; zero or null = none
     * @param onChange  also take a screenshot when the screen changed
     * @param minGap    minimum time between two screenshots taken for changes
     * @param maxShots  screenshots per command, including the final one
     */
    public AutomationScreenshotScheduler(Clock clock, Duration interval, boolean onChange, Duration minGap,
                                         int maxShots, Supplier<Screen> screen, Sink sink) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.interval = interval != null && !interval.isNegative() ? interval : Duration.ZERO;
        this.onChange = onChange;
        this.minGap = minGap != null && !minGap.isNegative() ? minGap : Duration.ZERO;
        this.maxShots = Math.max(1, maxShots);
        this.screen = Objects.requireNonNull(screen, "screen");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.lastShotAt = this.clock.instant();
    }

    /** True when neither periodic nor change screenshots are configured (only the final one). */
    public boolean finalOnly() {
        return interval.isZero() && !onChange;
    }

    /** Checks the screen and takes a screenshot when one is due. */
    public synchronized void tick() {
        if (finished || shots >= maxShots - 1) {
            return;
        }
        Screen current = screen.get();
        if (current == null) {
            return;
        }
        Instant now = clock.instant();
        boolean changed = current.changeCount() != lastSeenChangeCount;
        lastSeenChangeCount = current.changeCount();
        Duration sinceLast = Duration.between(lastShotAt, now);
        boolean periodicDue = !interval.isZero() && sinceLast.compareTo(interval) >= 0;
        boolean changeDue = onChange && changed && sinceLast.compareTo(minGap) >= 0;
        if (!periodicDue && !changeDue) {
            return;
        }
        String key = current.contentKey();
        if (key != null && key.equals(lastShotKey)) {
            lastShotAt = now; // unchanged screen: wait another interval
            return;
        }
        take(false, key, now);
    }

    /** The final screenshot, unless the screen equals the last one taken. */
    public synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        Screen current = screen.get();
        String key = current != null ? current.contentKey() : null;
        if (key != null && key.equals(lastShotKey)) {
            return;
        }
        if (key != null && key.isBlank() && shots == 0) {
            return; // nothing was ever on screen
        }
        take(true, key, clock.instant());
    }

    public synchronized int shotsTaken() {
        return shots;
    }

    private void take(boolean finalShot, String key, Instant now) {
        shots++;
        lastShotAt = now;
        lastShotKey = key;
        sink.take(finalShot);
    }
}
