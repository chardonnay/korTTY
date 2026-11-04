package de.kortty.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * How far a re-evaluation run is and how long it still needs.
 *
 * <p>A run is a known sequence of AI calls, planned per journal before the first one: the windows of
 * the capture log, the wrap-up, the AI title and the screenshots. The bar therefore advances with the
 * planned work rather than with whole journals, which for a single journal would leave it empty until
 * the very end. Each call is weighted by its prompt size, since a local model spends most of its time
 * reading the prompt.</p>
 *
 * <p>The rate, seconds per weight unit, is learned from the calls that finished, so the estimate follows
 * the model actually in use: a cloud model and a local model on a laptop differ by an order of magnitude.
 * Until a call has finished there is no rate and no remaining time, unless an earlier run with the same
 * profile left one. While a call runs, the bar creeps towards the call's expected end and stops short of
 * it, so a slow call never looks done.</p>
 *
 * <p>Thread-safe; the clock is injected so tests need no waiting.</p>
 */
public final class SessionJournalReevaluationProgress {

    /** What a planned AI call does, for the step line under the bar. */
    public enum StepKind { WINDOW, WRAP_UP, TITLE, SCREENSHOT }

    /** One planned AI call. */
    public record Step(StepKind kind, double units) {
    }

    /**
     * The state at one moment. {@code remaining} is null while no rate is known. {@code stepNumber} and
     * {@code stepCount} count the calls of {@code stepKind} in the current journal (both 0 between calls).
     */
    public record Estimate(double fraction, Duration elapsed, Duration remaining,
                           StepKind stepKind, int stepNumber, int stepCount) {

        public int percent() {
            return (int) Math.floor(Math.max(0, Math.min(1, fraction)) * 100);
        }
    }

    /** Log characters worth one unit, i.e. as long to read as an answer takes to write. */
    static final double CHARS_PER_UNIT = 12_000;
    static final double WRAP_UP_UNITS = 1.3;
    static final double TITLE_UNITS = 1.0;
    static final double SCREENSHOT_UNITS = 1.5;
    /** A call that overruns its estimate holds the bar at this share of its weight. */
    static final double CREEP_CAP = 0.95;

    private final Clock clock;
    private final Instant startedAt;
    private final Double priorSecondsPerUnit;
    private final List<Deque<Step>> pending = new ArrayList<>();
    private final List<List<Step>> planned = new ArrayList<>();
    private double totalUnits;
    private double doneUnits;
    private double finishedSeconds;
    private double finishedUnits;
    private int journal = -1;
    private Step current;
    private Instant currentStartedAt;

    /**
     * @param priorSecondsPerUnit the rate an earlier run with the same profile measured; null when unknown
     */
    public SessionJournalReevaluationProgress(Clock clock, Double priorSecondsPerUnit) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.startedAt = this.clock.instant();
        this.priorSecondsPerUnit = priorSecondsPerUnit != null && priorSecondsPerUnit > 0 ? priorSecondsPerUnit : null;
    }

    /** The planned calls of a re-evaluation: windows, wrap-up, title, then {@code screenshots} pictures. */
    public static List<Step> steps(SessionJournalSummarizer.ReevaluationPlan plan, int screenshots) {
        List<Step> steps = new ArrayList<>();
        if (plan != null) {
            for (int chars : plan.windowPromptChars()) {
                steps.add(new Step(StepKind.WINDOW, windowUnits(chars)));
            }
            if (plan.wrapUp()) {
                steps.add(new Step(StepKind.WRAP_UP, WRAP_UP_UNITS));
            }
            if (plan.title()) {
                steps.add(new Step(StepKind.TITLE, TITLE_UNITS));
            }
        }
        for (int i = 0; i < screenshots; i++) {
            steps.add(new Step(StepKind.SCREENSHOT, SCREENSHOT_UNITS));
        }
        return steps;
    }

    static double windowUnits(int promptChars) {
        return 1 + Math.max(0, promptChars) / CHARS_PER_UNIT;
    }

    /** Adds the planned calls of the next journal; journals are numbered in the order they are planned. */
    public synchronized int planJournal(List<Step> steps) {
        List<Step> copy = List.copyOf(steps);
        planned.add(copy);
        pending.add(new ArrayDeque<>(copy));
        for (Step step : copy) {
            totalUnits += step.units();
        }
        return planned.size() - 1;
    }

    public synchronized void journalStarted(int index) {
        journal = index;
        current = null;
    }

    /**
     * Drops the planned calls of the current journal that come before its screenshots: the summary
     * pass is over, whatever it did not call is no longer work to come.
     */
    public synchronized void screenshotsStarted() {
        Deque<Step> queue = queue();
        if (queue == null) {
            return;
        }
        while (!queue.isEmpty() && queue.peekFirst().kind() != StepKind.SCREENSHOT) {
            totalUnits -= queue.pollFirst().units();
        }
    }

    /**
     * A call begins: it takes the next planned step of the journal, or one unit when none is left. Returns
     * the token to pass to {@link #callFinished(Object)}. A call that is still open counts as finished now:
     * a call the summarizer gave up on after its timeout may only return later.
     */
    public synchronized Object callStarted() {
        if (current != null) {
            finishCurrent();
        }
        Deque<Step> queue = queue();
        Step step = queue != null ? queue.pollFirst() : null;
        if (step == null) {
            // An unplanned call: it is work all the same.
            step = new Step(null, 1.0);
            totalUnits += step.units();
        }
        current = step;
        currentStartedAt = clock.instant();
        return step;
    }

    /** The call {@code token} returned; ignored when that call was already counted. */
    public synchronized void callFinished(Object token) {
        if (current != null && current == token) {
            finishCurrent();
        }
    }

    private void finishCurrent() {
        double seconds = Duration.between(currentStartedAt, clock.instant()).toMillis() / 1000.0;
        doneUnits += current.units();
        finishedUnits += current.units();
        finishedSeconds += Math.max(0, seconds);
        current = null;
        currentStartedAt = null;
    }

    /** The journal is done: calls it planned but never made are dropped from the work to come. */
    public synchronized void journalFinished(int index) {
        if (index >= 0 && index < pending.size()) {
            Deque<Step> queue = pending.get(index);
            while (!queue.isEmpty()) {
                totalUnits -= queue.pollFirst().units();
            }
        }
        if (index == journal) {
            current = null;
            currentStartedAt = null;
        }
    }

    /** Seconds per unit measured by this run, else the prior one; null when neither exists. */
    public synchronized Double secondsPerUnit() {
        if (finishedUnits > 0 && finishedSeconds > 0) {
            return finishedSeconds / finishedUnits;
        }
        return priorSecondsPerUnit;
    }

    /** The rate this run measured itself, to hand to the next run with the same profile; null when none. */
    public synchronized Double measuredSecondsPerUnit() {
        return finishedUnits > 0 && finishedSeconds > 0 ? finishedSeconds / finishedUnits : null;
    }

    public synchronized Estimate estimate() {
        Instant now = clock.instant();
        Duration elapsed = Duration.between(startedAt, now);
        Double rate = secondsPerUnit();
        double currentUnits = current != null ? current.units() : 0;
        double inCall = current != null ? Duration.between(currentStartedAt, now).toMillis() / 1000.0 : 0;
        double creep = 0;
        Duration remaining = null;
        if (rate != null) {
            double expected = rate * currentUnits;
            creep = expected > 0 ? Math.min(CREEP_CAP, inCall / expected) : 0;
            double future = Math.max(0, totalUnits - doneUnits - currentUnits);
            double seconds = rate * future + Math.max(0, expected - inCall);
            remaining = Duration.ofMillis(Math.round(seconds * 1000));
        }
        double fraction = totalUnits > 0 ? (doneUnits + creep * currentUnits) / totalUnits : 0;
        fraction = Math.max(0, Math.min(1, fraction));
        StepKind kind = current != null ? current.kind() : null;
        int number = 0;
        int count = 0;
        if (kind != null && journal >= 0 && journal < planned.size()) {
            for (Step step : planned.get(journal)) {
                if (step.kind() == kind) {
                    count++;
                }
            }
            int left = 0;
            for (Step step : pending.get(journal)) {
                if (step.kind() == kind) {
                    left++;
                }
            }
            number = count - left;
        }
        return new Estimate(fraction, elapsed, remaining, kind, number, count);
    }

    private Deque<Step> queue() {
        return journal >= 0 && journal < pending.size() ? pending.get(journal) : null;
    }
}
