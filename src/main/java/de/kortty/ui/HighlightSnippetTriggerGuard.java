package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Decides whether a highlight rule that runs a snippet ({@code HighlightRule.Action.RUN_SNIPPET}) may run
 * it now in the pane where its pattern appeared. FX-free and clock-injected, so every rule below is
 * unit-tested; {@link HighlightSnippetTrigger} asks, shows the confirmation and types the snippet.
 *
 * <p>In this order:
 * <ul>
 *   <li>Nothing while triggers are not allowed: the enterprise policy key {@code terminal-triggers} or the
 *       user's switch in Settings → Terminal is off ({@link Verdict#NOT_ALLOWED}).</li>
 *   <li>Nothing for a hit on the line the cursor is on ({@link Verdict#CURSOR_LINE}): that is the command
 *       the user is typing, or a program's question waiting for an answer — running a snippet there would
 *       be the remote-triggered auto-answer korTTY does not do.</li>
 *   <li>Nothing for output that answers keys mirrored into the pane by broadcast mode or multi-exec
 *       moments ago ({@link Verdict#MIRRORED_INPUT}), and nothing while a korTTY terminal-agent run or a
 *       coding agent works in the pane, which would read the snippet as its own input
 *       ({@link Verdict#AGENT_BUSY}).</li>
 *   <li><b>Loop guard.</b> When rules ran snippets {@value #MAX_RUNS_IN_A_ROW} times in a row in a pane,
 *       each within {@link #LOOP_WINDOW_MILLIS} of the one before — a snippet whose output matches its own
 *       rule, or two rules that keep setting each other off — the next match stops all snippet runs in
 *       that pane ({@link Verdict#LOOP_STOPPED}, reported once so the user is told) and every match after
 *       it is dropped ({@link Verdict#STOPPED}). The stop ends once {@link #LOOP_WINDOW_MILLIS} pass
 *       without a match in the pane; each dropped match starts that wait anew.</li>
 *   <li><b>Cooldown.</b> A rule runs its snippet at most once every {@link #COOLDOWN_MILLIS} in a pane
 *       ({@link Verdict#COOLDOWN}), and no rule runs one within {@link #SETTLE_MILLIS} of the pane's last
 *       run ({@link Verdict#SETTLING}): the snippet's own banner, echo and first output cannot start the
 *       next run.</li>
 *   <li><b>Confirmation.</b> The first time a rule wants to run a snippet in a connection, the user is
 *       asked ({@link Verdict#ASK}); while that question is open, further matches are dropped
 *       ({@link Verdict#ASKING}). The answer ({@link #answer}) holds for that connection, rule and snippet
 *       until korTTY quits; a refusal drops every later match ({@link Verdict#DECLINED}).</li>
 *   <li>Otherwise {@link Verdict#RUN}; the caller reports the run with {@link #ran} once the snippet was
 *       typed, which starts the cooldown and counts towards the loop guard.</li>
 * </ul>
 *
 * <p>Not thread-safe: call it from one thread, the UI thread in the application. Panes are kept weakly.
 */
final class HighlightSnippetTriggerGuard {

    /** The shortest time between two runs of the same rule's snippet in a pane: 30 seconds. */
    static final long COOLDOWN_MILLIS = 30_000L;

    /** How long after a run in a pane no rule runs a snippet there: its own output settles first. */
    static final long SETTLE_MILLIS = 5_000L;

    /**
     * How close runs in a pane must follow each other to count as a chain for the loop guard, and how long
     * a stopped pane must stay without a match before snippets run there again: 2 minutes.
     */
    static final long LOOP_WINDOW_MILLIS = 120_000L;

    /** How many runs in a row, each within {@link #LOOP_WINDOW_MILLIS} of the one before, a pane allows. */
    static final int MAX_RUNS_IN_A_ROW = 3;

    /** What a match leads to. */
    enum Verdict {
        /** Run the snippet now, then report it with {@link #ran}. */
        RUN,
        /** Ask the user first (the first use for the connection, rule and snippet), then {@link #answer}. */
        ASK,
        /** The loop guard stopped snippet runs in the pane just now: tell the user, once. */
        LOOP_STOPPED,
        /** Triggers are switched off by the policy or by the user. */
        NOT_ALLOWED,
        /** The hit is on the line the cursor is on. */
        CURSOR_LINE,
        /** The output answers keys mirrored into the pane moments ago. */
        MIRRORED_INPUT,
        /** A terminal-agent run or a coding agent works in the pane. */
        AGENT_BUSY,
        /** The loop guard keeps snippet runs in the pane stopped. */
        STOPPED,
        /** The rule ran its snippet in the pane less than {@link #COOLDOWN_MILLIS} ago. */
        COOLDOWN,
        /** A snippet ran in the pane less than {@link #SETTLE_MILLIS} ago. */
        SETTLING,
        /** The question for this connection, rule and snippet is still open. */
        ASKING,
        /** The user refused this connection, rule and snippet. */
        DECLINED;

        /** Whether the snippet runs, at once or once the user agrees. */
        boolean proceeds() {
            return this == RUN || this == ASK;
        }
    }

    /**
     * One rule's match in one pane.
     *
     * @param pane          the pane, kept weakly; compared by identity
     * @param connectionKey the connection the pane's session belongs to ({@link #connectionKey})
     * @param ruleId        the rule that matched
     * @param snippetId     the snippet the rule runs
     * @param cursorLine    whether the hit is on the line the cursor is on
     * @param mirroredInput whether keys mirrored into the pane by broadcast mode or multi-exec reached it
     *                      moments ago
     * @param agentBusy     whether a korTTY terminal-agent run or a coding agent works in the pane
     */
    record Request(Object pane, String connectionKey, String ruleId, String snippetId, boolean cursorLine,
            boolean mirroredInput, boolean agentBusy) {

        Request {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(connectionKey, "connectionKey");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(snippetId, "snippetId");
        }

        /** What the confirmation is remembered under. */
        String approvalKey() {
            return connectionKey + '\u0000' + ruleId + '\u0000' + snippetId;
        }
    }

    /** What the guard knows about one pane. */
    private static final class PaneRuns {

        /** Per rule id, when it last ran its snippet here. */
        private final Map<String, Long> lastRunByRule = new HashMap<>();

        /** When a snippet last ran here, or {@code null}. */
        private Long lastRun;

        /** Runs in a row, each within {@link #LOOP_WINDOW_MILLIS} of the one before. */
        private int runsInARow;

        /** Whether the loop guard stopped snippet runs here. */
        private boolean stopped;

        /** When the latest match arrived while stopped. */
        private long lastStoppedMatch;

        /** What the user was already told about here ({@link #tellOnce}). */
        private final Set<String> told = new HashSet<>();
    }

    private final LongSupplier clockMillis;

    private final BooleanSupplier triggersAllowed;

    private final Map<Object, PaneRuns> panes = new WeakHashMap<>();

    /** Per {@link Request#approvalKey()}, the user's answer. */
    private final Map<String, Boolean> answers = new HashMap<>();

    /** The {@link Request#approvalKey()}s whose question is open. */
    private final Set<String> asking = new HashSet<>();

    /**
     * @param clockMillis     a clock in milliseconds that never goes back
     * @param triggersAllowed whether trigger actions may run now
     *                        ({@link HighlightTriggerDispatcher#triggersAllowed})
     */
    HighlightSnippetTriggerGuard(LongSupplier clockMillis, BooleanSupplier triggersAllowed) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.triggersAllowed = Objects.requireNonNull(triggersAllowed, "triggersAllowed");
    }

    /**
     * Decides about one match (see the class comment). {@link Verdict#ASK} marks the question as open and
     * {@link Verdict#LOOP_STOPPED} stops the pane; nothing else changes until {@link #ran} or {@link #answer}.
     */
    Verdict decide(Request request) {
        Objects.requireNonNull(request, "request");
        if (!allowed()) {
            return Verdict.NOT_ALLOWED;
        }
        if (request.cursorLine()) {
            return Verdict.CURSOR_LINE;
        }
        if (request.mirroredInput()) {
            return Verdict.MIRRORED_INPUT;
        }
        if (request.agentBusy()) {
            return Verdict.AGENT_BUSY;
        }
        long now = clockMillis.getAsLong();
        PaneRuns runs = panes.computeIfAbsent(request.pane(), unused -> new PaneRuns());
        if (runs.stopped) {
            if (now - runs.lastStoppedMatch < LOOP_WINDOW_MILLIS) {
                runs.lastStoppedMatch = now;
                return Verdict.STOPPED;
            }
            // Quiet for a whole window: whatever kept matching has ended.
            runs.stopped = false;
            runs.runsInARow = 0;
            runs.told.clear();
        }
        Long ruleRun = runs.lastRunByRule.get(request.ruleId());
        if (ruleRun != null && now - ruleRun < COOLDOWN_MILLIS) {
            return Verdict.COOLDOWN;
        }
        if (runs.lastRun != null && now - runs.lastRun < SETTLE_MILLIS) {
            return Verdict.SETTLING;
        }
        if (runs.runsInARow >= MAX_RUNS_IN_A_ROW && runs.lastRun != null && now - runs.lastRun <= LOOP_WINDOW_MILLIS) {
            runs.stopped = true;
            runs.lastStoppedMatch = now;
            return Verdict.LOOP_STOPPED;
        }
        String key = request.approvalKey();
        Boolean answer = answers.get(key);
        if (answer != null) {
            return answer ? Verdict.RUN : Verdict.DECLINED;
        }
        if (!asking.add(key)) {
            return Verdict.ASKING;
        }
        return Verdict.ASK;
    }

    /** The snippet of {@code ruleId} was typed into {@code pane} just now: starts its cooldown, counts the run. */
    void ran(Object pane, String ruleId) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(ruleId, "ruleId");
        long now = clockMillis.getAsLong();
        PaneRuns runs = panes.computeIfAbsent(pane, unused -> new PaneRuns());
        boolean chained = runs.lastRun != null && now - runs.lastRun <= LOOP_WINDOW_MILLIS;
        runs.runsInARow = chained ? runs.runsInARow + 1 : 1;
        runs.lastRun = now;
        runs.lastRunByRule.put(ruleId, now);
    }

    /**
     * The user answered the question {@link Verdict#ASK} asked for {@code request}: {@code allowed} holds for
     * its connection, rule and snippet until korTTY quits. Ask {@link #decide} again before running, as the
     * pane may have changed while the question was open.
     */
    void answer(Request request, boolean allowed) {
        String key = request.approvalKey();
        asking.remove(key);
        answers.put(key, allowed);
    }

    /** The question {@link Verdict#ASK} asked for {@code request} could not be shown: the next match asks again. */
    void withdraw(Request request) {
        asking.remove(request.approvalKey());
    }

    /**
     * Whether the user still has to be told {@code what} about {@code pane} (a missing snippet, say), so a
     * rule that keeps matching tells it once rather than at every match. Told again after the loop guard's
     * stop of the pane ended.
     */
    boolean tellOnce(Object pane, String what) {
        return panes.computeIfAbsent(Objects.requireNonNull(pane, "pane"), unused -> new PaneRuns()).told.add(what);
    }

    /**
     * What the confirmation of a pane's connection is remembered under: the saved connection's id, else
     * what names its session ({@code user@host}, a local shell's name).
     */
    static String connectionKey(@Nullable String connectionId, @Nullable String fallbackName) {
        if (connectionId != null && !connectionId.isBlank()) {
            return "id:" + connectionId.trim();
        }
        return "name:" + (fallbackName != null ? fallbackName.trim() : "");
    }

    private boolean allowed() {
        try {
            return triggersAllowed.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
