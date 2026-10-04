package de.kortty.core.sftp.transfer;

import java.time.Duration;

/**
 * Smoothed transfer rate and remaining time for one transfer (or a whole queue).
 *
 * <p>The rate is an exponentially weighted moving average over samples at least
 * {@code minSampleNanos} apart, so a burst of tiny buffers does not make it jump. The time is passed
 * in by the caller ({@link System#nanoTime()} in production), which keeps the meter deterministic in
 * tests. The remaining time is {@code null} while it would only be a guess: before the first rate,
 * for an unknown size, when the rate is below the floor, or when nothing arrived for
 * {@code stallNanos}.
 *
 * <p>Thread-safe; the worker updates it and the UI reads it.
 */
public final class SpeedMeter {

    /** Time constant of the moving average. */
    public static final Duration DEFAULT_TIME_CONSTANT = Duration.ofSeconds(3);
    /** Minimum spacing of two rate samples. */
    public static final Duration DEFAULT_MIN_SAMPLE = Duration.ofMillis(100);
    /** Below this rate (bytes per second) no remaining time is shown. */
    public static final double DEFAULT_ETA_FLOOR = 512;
    /** After this long without new bytes the transfer counts as stalled. */
    public static final Duration DEFAULT_STALL = Duration.ofSeconds(5);

    private final double timeConstantNanos;
    private final long minSampleNanos;
    private final double etaFloorBytesPerSecond;
    private final long stallNanos;

    private boolean started;
    private boolean hasRate;
    private double rate;
    private long sampleStartNanos;
    private long sampleStartBytes;
    private long lastBytes;
    private long lastUpdateNanos;
    private long lastProgressNanos;

    public SpeedMeter() {
        this(DEFAULT_TIME_CONSTANT, DEFAULT_MIN_SAMPLE, DEFAULT_ETA_FLOOR, DEFAULT_STALL);
    }

    public SpeedMeter(Duration timeConstant, Duration minSample, double etaFloorBytesPerSecond, Duration stall) {
        if (timeConstant.isNegative() || timeConstant.isZero()) {
            throw new IllegalArgumentException("timeConstant must be positive");
        }
        this.timeConstantNanos = timeConstant.toNanos();
        this.minSampleNanos = Math.max(1, minSample.toNanos());
        this.etaFloorBytesPerSecond = Math.max(0, etaFloorBytesPerSecond);
        this.stallNanos = Math.max(1, stall.toNanos());
    }

    /**
     * Records that {@code bytesDone} bytes are done at {@code nowNanos}. Call it on every progress
     * event and also periodically without progress, so a stall is noticed.
     */
    public synchronized void update(long bytesDone, long nowNanos) {
        if (!started || bytesDone < sampleStartBytes || nowNanos < sampleStartNanos) {
            // First sample, or the transfer restarted: begin a new baseline, keep the old rate.
            started = true;
            sampleStartNanos = nowNanos;
            sampleStartBytes = bytesDone;
            lastBytes = bytesDone;
            lastUpdateNanos = nowNanos;
            lastProgressNanos = nowNanos;
            return;
        }
        if (bytesDone > lastBytes) {
            lastProgressNanos = nowNanos;
        }
        lastBytes = bytesDone;
        lastUpdateNanos = nowNanos;
        long elapsed = nowNanos - sampleStartNanos;
        if (elapsed < minSampleNanos) {
            return;
        }
        double instant = (bytesDone - sampleStartBytes) * 1_000_000_000d / elapsed;
        if (hasRate) {
            double alpha = 1 - Math.exp(-elapsed / timeConstantNanos);
            rate += alpha * (instant - rate);
        } else {
            rate = instant;
            hasRate = true;
        }
        sampleStartNanos = nowNanos;
        sampleStartBytes = bytesDone;
    }

    /** The smoothed rate in bytes per second; 0 before the first sample and while stalled. */
    public synchronized double bytesPerSecond() {
        if (!hasRate || stalled()) {
            return 0;
        }
        return Math.max(0, rate);
    }

    /**
     * Time left until {@code totalBytes}, or {@code null} when the size is unknown ({@code < 0}),
     * no rate is known yet, the rate is below the floor, or the transfer is stalled.
     */
    public synchronized Duration eta(long totalBytes) {
        if (totalBytes < 0 || !started) {
            return null;
        }
        long remaining = totalBytes - lastBytes;
        if (remaining <= 0) {
            return Duration.ZERO;
        }
        double current = bytesPerSecond();
        if (current <= 0 || current < etaFloorBytesPerSecond) {
            return null;
        }
        double nanos = remaining * 1_000_000_000d / current;
        if (nanos >= Long.MAX_VALUE) {
            return null;
        }
        return Duration.ofNanos((long) nanos);
    }

    /** Whether no new bytes arrived for the stall time. */
    public synchronized boolean isStalled() {
        return stalled();
    }

    private boolean stalled() {
        return started && lastUpdateNanos - lastProgressNanos >= stallNanos;
    }
}
