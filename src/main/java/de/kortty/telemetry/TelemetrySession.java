package de.kortty.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Aptabase session id handling: {@code epochSeconds + 8 random digits},
 * rotated after one hour without tracked activity (matches the official SDKs).
 */
final class TelemetrySession {

    static final Duration IDLE_TIMEOUT = Duration.ofHours(1);

    private final Clock clock;
    private Instant lastActivity;
    private String sessionId;

    TelemetrySession(Clock clock) {
        this.clock = clock;
    }

    synchronized String touchAndGetId() {
        Instant now = clock.instant();
        if (sessionId == null || lastActivity == null
                || Duration.between(lastActivity, now).compareTo(IDLE_TIMEOUT) > 0) {
            sessionId = newSessionId(now.getEpochSecond(), ThreadLocalRandom.current().nextInt(100_000_000));
        }
        lastActivity = now;
        return sessionId;
    }

    static String newSessionId(long epochSeconds, int random) {
        return epochSeconds + String.format("%08d", Math.floorMod(random, 100_000_000));
    }
}
