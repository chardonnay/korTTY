package de.kortty.shellintegration;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/**
 * Watches terminal panes for activity and for silence, the two runtime switches of a terminal tab's
 * right-click menu (<b>Monitor for Activity</b>, <b>Monitor for Silence</b>).
 *
 * <ul>
 *   <li><b>Activity</b>: output arrives after the pane was quiet for at least
 *       {@link #ACTIVITY_QUIET_GAP} while the user is not looking at its tab. It is reported once,
 *       and again only after the user has looked at the tab, so a pane that keeps printing in the
 *       background reports once and not on every line.</li>
 *   <li><b>Silence</b>: a pane that printed something stays without output for the silence
 *       threshold. It is reported once, and again only after the next output, so a finished build
 *       reports when it stops and not every few seconds after that. Switching silence monitoring on
 *       counts output that came within the threshold before, so the pane that has just stopped
 *       printing still reports.</li>
 *   <li><b>Mirrored keys</b>: output that arrives while keys typed in another pane reached this one
 *       through broadcast mode or multi-exec moments ago ({@link PaneFacts#mirroredInput()}) is the
 *       shell's answer to those keys, typically their echo. It is no activity, and it does not start
 *       the silence count of a pane that was not already counting: typing into a dozen panes at
 *       once makes none of them report. It still ends the quiet spell, so the next activity needs a
 *       new one.</li>
 * </ul>
 *
 * <p>What the user sees is decided elsewhere ({@link TerminalNotificationPolicy}): nothing in a tab
 * the user is looking at, otherwise the tab's attention mark and a desktop notification. The monitor
 * only reports. Only the pane's own output counts, so the blank line an agent keep-alive prints, or
 * the echo of what the user types into the pane itself, counts as output too.
 *
 * <p>Driven by a timer every {@link #POLL_INTERVAL} that runs only while some pane is watched; each
 * call to {@link #poll} takes the pane's {@link PaneOutputClock}. A pane polled after a longer pause
 * starts afresh, as one whose watch was just switched on. FX-free and clock-injected. Not
 * thread-safe: call it from one thread, the UI thread in the application. Panes are kept weakly, so
 * a closed one needs no clean-up.
 */
public final class PaneActivityMonitor {

    /** How often the panes are polled. */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /** How long a pane has to be quiet before its output counts as activity. */
    public static final Duration ACTIVITY_QUIET_GAP = Duration.ofSeconds(10);

    /**
     * How recently mirrored keys must have reached a pane for its output to count as their answer
     * ({@link PaneFacts#mirroredInput()}): the round trip of
     * {@link TerminalNotificationPolicy#MIRRORED_ECHO_WINDOW}, plus the time the output may have
     * waited for the next poll.
     */
    public static final Duration MIRRORED_OUTPUT_WINDOW =
        TerminalNotificationPolicy.MIRRORED_ECHO_WINDOW.plus(POLL_INTERVAL);

    /** A pane not polled for this long starts afresh at its next poll. */
    static final Duration MAX_POLL_GAP = Duration.ofSeconds(5);

    /** The shortest silence threshold, in seconds. */
    public static final int MIN_SILENCE_SECONDS = 5;

    /** The longest silence threshold, in seconds: one hour. */
    public static final int MAX_SILENCE_SECONDS = 3600;

    /** The silence threshold of a fresh installation, in seconds. */
    public static final int DEFAULT_SILENCE_SECONDS = 30;

    /** What a pane did. */
    public enum Event {
        /** Output after at least {@link #ACTIVITY_QUIET_GAP} of quiet, in a tab the user is not looking at. */
        ACTIVITY,
        /** No output for the silence threshold after output. */
        SILENCE
    }

    /**
     * What a pane is watched for: the switches of its tab.
     *
     * @param activity whether activity is reported
     * @param silence  whether silence is reported
     */
    public record Watch(boolean activity, boolean silence) {

        /** Watched for nothing. */
        public static final Watch NONE = new Watch(false, false);

        /** Whether the pane is watched for anything. */
        public boolean any() {
            return activity || silence;
        }
    }

    /**
     * What is known about the pane when it is polled.
     *
     * @param seen          whether the user is looking at the pane's tab: its window is in front and
     *                      the tab is selected
     * @param mirroredInput whether keys typed in another pane reached this one through broadcast mode
     *                      or multi-exec within {@link #MIRRORED_OUTPUT_WINDOW}
     */
    public record PaneFacts(boolean seen, boolean mirroredInput) {
    }

    /** One pane's watch. */
    private static final class PaneWatch {
        Watch watch = Watch.NONE;
        long lastPollNanos;
        long lastOutputNanos = PaneOutputClock.NEVER;
        boolean activityArmed;
        boolean silenceArmed;
    }

    private final LongSupplier clockNanos;

    private final Map<PaneOutputClock, PaneWatch> panes = new WeakHashMap<>();

    /** A monitor on {@link System#nanoTime()}, the clock {@link PaneOutputClock} uses by default. */
    public PaneActivityMonitor() {
        this(System::nanoTime);
    }

    /** @param clockNanos the clock of the panes' {@link PaneOutputClock}s, in nanoseconds */
    public PaneActivityMonitor(LongSupplier clockNanos) {
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    /** {@code seconds} within {@value #MIN_SILENCE_SECONDS}..{@value #MAX_SILENCE_SECONDS}. */
    public static int clampSilenceSeconds(int seconds) {
        return Math.max(MIN_SILENCE_SECONDS, Math.min(MAX_SILENCE_SECONDS, seconds));
    }

    /**
     * Polls one pane: takes what its clock saw since the last poll and reports what that means.
     * A pane watched for nothing is forgotten, so switching a watch on again starts afresh.
     *
     * @param pane         the pane's output clock, which also identifies it; kept weakly
     * @param watch        what its tab is watched for now
     * @param facts        what is known about it now
     * @param silenceAfter the silence threshold now ({@code GlobalSettings.terminalSilenceSeconds})
     * @return what to report, often nothing; never {@code null}
     */
    public Set<Event> poll(PaneOutputClock pane, Watch watch, PaneFacts facts, Duration silenceAfter) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(silenceAfter, "silenceAfter");
        if (!watch.any()) {
            panes.remove(pane);
            return Collections.emptySet();
        }
        long now = clockNanos.getAsLong();
        long silenceNanos = silenceAfter.toNanos();
        PaneWatch state = panes.get(pane);
        if (state == null || now - state.lastPollNanos > MAX_POLL_GAP.toNanos()) {
            panes.put(pane, startWatching(pane, watch, now, silenceNanos));
            return Collections.emptySet();
        }
        if (watch.activity() && !state.watch.activity()) {
            state.activityArmed = true;
        }
        if (watch.silence() && !state.watch.silence()) {
            state.silenceArmed = printedWithin(state.lastOutputNanos, now, silenceNanos);
        }

        Set<Event> events = EnumSet.noneOf(Event.class);
        PaneOutputClock.Sample sample = pane.take();
        if (sample.newOutput()) {
            if (!facts.mirroredInput()) {
                if (watch.activity() && state.activityArmed && !facts.seen()
                        && quietFor(state.lastOutputNanos, sample.firstNanos(), ACTIVITY_QUIET_GAP.toNanos())) {
                    events.add(Event.ACTIVITY);
                    state.activityArmed = false;
                }
                if (watch.silence()) {
                    state.silenceArmed = true;
                }
            }
            state.lastOutputNanos = sample.lastNanos();
        }
        if (facts.seen()) {
            // The user looked: the next quiet spell may report again.
            state.activityArmed = true;
        }
        if (watch.silence() && state.silenceArmed && state.lastOutputNanos != PaneOutputClock.NEVER
                && now - state.lastOutputNanos >= silenceNanos) {
            events.add(Event.SILENCE);
            state.silenceArmed = false;
        }
        state.watch = watch;
        state.lastPollNanos = now;
        return events;
    }

    /** Forgets a pane, so its next poll starts afresh. */
    public void forget(PaneOutputClock pane) {
        if (pane != null) {
            panes.remove(pane);
        }
    }

    /** The number of panes watched now. For tests. */
    int watchedPaneCount() {
        return panes.size();
    }

    /**
     * A pane that starts being watched: what arrived before counts as the past, so only output from
     * now on can be activity; silence counts from output that came within the threshold.
     */
    private static PaneWatch startWatching(PaneOutputClock pane, Watch watch, long now, long silenceNanos) {
        PaneWatch state = new PaneWatch();
        state.lastOutputNanos = pane.take().lastNanos();
        state.watch = watch;
        state.lastPollNanos = now;
        state.activityArmed = true;
        state.silenceArmed = printedWithin(state.lastOutputNanos, now, silenceNanos);
        return state;
    }

    private static boolean printedWithin(long lastOutputNanos, long now, long windowNanos) {
        return lastOutputNanos != PaneOutputClock.NEVER && now - lastOutputNanos < windowNanos;
    }

    /** Whether nothing arrived for at least {@code gapNanos} before {@code firstNewNanos}. */
    private static boolean quietFor(long lastOutputNanos, long firstNewNanos, long gapNanos) {
        return lastOutputNanos == PaneOutputClock.NEVER || firstNewNanos - lastOutputNanos >= gapNanos;
    }
}
