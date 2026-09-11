package de.kortty.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Opt-in wall-clock tracing for UI-latency investigations. Off by default and free when off: the
 * {@link #ENABLED} flag is a compile-time constant of this class, every {@code begin} returns a
 * shared no-op span, and no call site allocates. Enabled with the system property
 * {@code -Dkortty.ui.perf=true} or the environment variable {@code KORTTY_UI_PERF=1}; the latter
 * also works for the packaged launcher, whose JVM options are baked into the signed bundle.
 *
 * <p>Every span logs one INFO line on the logger {@code de.kortty.ui.perf}, which the default
 * logback configuration writes to the console and to {@code ~/.kortty/logs/kortty.log}, e.g.
 * {@code perf GlobalSettingsManager.save: context=138ms marshal=21ms write=3ms total=162ms}.</p>
 *
 * <p>Deliberately JavaFX-free so that {@code core}, {@code security} and {@code ui} can all use
 * it; the dialog lifecycle hooks live in {@code de.kortty.ui.DialogPerfTrace}.</p>
 */
public final class PerfTrace {

    /** Logger name, shared with the dialog hooks so one filter catches every perf line. */
    public static final String LOGGER_NAME = "de.kortty.ui.perf";

    public static final boolean ENABLED = resolveEnabled();

    private static final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);
    private static final Span NOOP = new Span(null, 0L);

    private PerfTrace() {
    }

    private static boolean resolveEnabled() {
        if (Boolean.getBoolean("kortty.ui.perf")) {
            return true;
        }
        String env = System.getenv("KORTTY_UI_PERF");
        return env != null && ("1".equals(env.trim()) || "true".equalsIgnoreCase(env.trim()));
    }

    /** Starts a span; the no-op instance when tracing is off. */
    public static Span begin(String what) {
        return ENABLED ? new Span(what, System.nanoTime()) : NOOP;
    }

    /** Logs a single already-measured duration under {@code what}. */
    public static void logDuration(String what, long nanos) {
        if (ENABLED) {
            logger.info("perf {}: total={}", what, millis(nanos));
        }
    }

    /** Logs a preformatted perf line. */
    public static void log(String line) {
        if (ENABLED) {
            logger.info("perf {}", line);
        }
    }

    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }

    /**
     * One measured operation. {@link #mark(String)} records the time since the previous mark (or
     * the start) under the given label; {@link #end()} logs the marks plus the total.
     */
    public static final class Span {
        private final String what;
        private final long start;
        private long last;
        private final StringBuilder marks;

        private Span(String what, long start) {
            this.what = what;
            this.start = start;
            this.last = start;
            this.marks = what != null ? new StringBuilder() : null;
        }

        public Span mark(String label) {
            if (marks == null) {
                return this;
            }
            long now = System.nanoTime();
            marks.append(' ').append(label).append('=').append(millis(now - last));
            last = now;
            return this;
        }

        public void end() {
            if (marks == null) {
                return;
            }
            logger.info("perf {}:{} total={}", what, marks, millis(System.nanoTime() - start));
        }
    }
}
