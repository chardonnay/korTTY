package de.kortty.shellintegration;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/**
 * Decides what a terminal pane's request for attention leads to: a mark on its tab, a desktop
 * notification, both or nothing.
 *
 * <p>The rules, the same for every kind:
 * <ul>
 *   <li>Nothing happens in a tab the user is looking at (its window in front, the tab selected).</li>
 *   <li>Otherwise the tab is always marked; the mark is silent and goes away when the tab is seen.</li>
 *   <li>A desktop notification needs its setting on, and comes at most once per notification slot
 *       within the kind's interval ({@link Kind#toastIntervalMillis()}); a request inside the
 *       interval only marks the tab. The slot is the pane for a bell and a program's notification,
 *       and the tab for a finished command (see {@link #decideCommandFinished}); for a pane that
 *       takes part in multi-exec the caller passes the multi-exec session instead of the pane or the
 *       tab for a bell and a finished command, so the members, whichever tabs hold them, share it.</li>
 *   <li>No bell notification and no program's notification for a pane in which a coding agent was
 *       detected while the coding-agent notifications are on: the agent rings the bell or asks for a
 *       notification when it waits for a decision, and its own notification already says so
 *       ({@link Kind#leftToCodingAgents()}).</li>
 *   <li>No bell and no activity counts while keys typed in another pane reached the pane through
 *       broadcast mode or multi-exec moments ago ({@link PaneState#mirroredInput()}): it answers
 *       those keys, typically a failed Tab completion that rings in every mirrored pane at once, or
 *       the echo every mirrored pane prints ({@link Kind#discountsMirroredInput()}).</li>
 * </ul>
 *
 * <p>Activity and silence ({@link Kind#ACTIVITY}, {@link Kind#SILENCE}) are reported only for a tab
 * the user asked to watch, with its right-click menu ({@link PaneActivityMonitor}), so that request
 * is their setting: they always notify, within their interval.
 *
 * <p>A highlight trigger ({@link Kind#TRIGGER}) exists only once the user gave a highlight rule the
 * notification action, so that rule is its setting too; it has {@link #decideTrigger}, whose slot is
 * the pane (or multi-exec session) together with the rule, so two rules do not silence each other.
 *
 * <p>A finished command ({@link Kind#COMMAND_FINISHED}) also has to have run at least the
 * threshold of the settings, and a command that a korTTY terminal-agent run typed into the pane
 * leads to nothing at all: the run reports its own commands. In a multi-exec session a command typed
 * once runs in every member, so it notifies once however far apart the members finish
 * ({@link MultiExecRun}).
 *
 * <p>FX-free and clock-injected. Not thread-safe: call it from one thread, the UI thread in the
 * application. Panes and tabs are kept weakly, so a closed one needs no clean-up.
 */
public final class TerminalNotificationPolicy {

    /** The shortest runtime the long-command threshold can be set to, in seconds. */
    public static final int MIN_COMMAND_FINISHED_SECONDS = 1;

    /** The longest runtime the long-command threshold can be set to, in seconds: one hour. */
    public static final int MAX_COMMAND_FINISHED_SECONDS = 3600;

    /**
     * The long-command threshold of a fresh installation, in seconds (decision D4 a): long enough
     * that routine {@code git} or package-manager runs do not notify.
     */
    public static final int DEFAULT_COMMAND_FINISHED_SECONDS = 30;

    /** Why a pane asks for attention. */
    public enum Kind {
        /** A program in the pane rang the terminal bell (BEL). */
        BELL(10_000L, true, true),
        /**
         * A command the shell marked with {@code OSC 133} finished ({@code D}) after running at
         * least the threshold. Its notification slot is the tab, so the same command finishing in
         * several panes that broadcast mirrors into notifies once.
         */
        COMMAND_FINISHED(10_000L, false, false),
        /**
         * A program in the pane asked for a desktop notification with {@code OSC 9} or
         * {@code OSC 777;notify}, typically a coding agent on a server that waits for an answer. Its
         * text is the program's, so it gets a shorter interval than the others but drops what comes
         * within it: a program cannot flood the desktop.
         */
        REMOTE(5_000L, true, false),
        /**
         * Output arrived after a quiet spell in a pane whose tab the user asked to watch for activity
         * ({@link PaneActivityMonitor.Event#ACTIVITY}). The echo of mirrored keys is no activity.
         */
        ACTIVITY(10_000L, false, true),
        /**
         * A pane whose tab the user asked to watch for silence stopped printing for the silence
         * threshold ({@link PaneActivityMonitor.Event#SILENCE}).
         */
        SILENCE(10_000L, false, false),
        /**
         * A highlight rule with the notification action matched new output in the pane
         * ({@code TerminalOutputHighlighter.LineMatch}). One notification per rule and pane every 30
         * seconds; output that answers mirrored keys does not count, so typing into multi-exec does not
         * make every member notify.
         */
        TRIGGER(30_000L, false, true);

        private final long toastIntervalMillis;
        private final boolean leftToCodingAgents;
        private final boolean discountsMirroredInput;

        Kind(long toastIntervalMillis, boolean leftToCodingAgents, boolean discountsMirroredInput) {
            this.toastIntervalMillis = toastIntervalMillis;
            this.leftToCodingAgents = leftToCodingAgents;
            this.discountsMirroredInput = discountsMirroredInput;
        }

        /** The shortest time between two desktop notifications of this kind for the same slot. */
        public long toastIntervalMillis() {
            return toastIntervalMillis;
        }

        /**
         * Whether a pane with a detected coding agent leaves this kind's notification to the agent's
         * own, while the coding-agent notifications are on.
         */
        public boolean leftToCodingAgents() {
            return leftToCodingAgents;
        }

        /**
         * Whether a request of this kind leads to nothing while mirrored keys reached the pane moments
         * ago ({@link PaneState#mirroredInput()}), because it most likely answers them.
         */
        public boolean discountsMirroredInput() {
            return discountsMirroredInput;
        }
    }

    /**
     * What a request leads to.
     *
     * @param badge whether the tab gets the attention mark
     * @param toast whether a desktop notification is shown
     */
    public record Decision(boolean badge, boolean toast) {

        /** Neither a mark nor a notification. */
        public static final Decision NONE = new Decision(false, false);
    }

    /**
     * What is known about the pane when it asks.
     *
     * @param seen            whether the user is looking at the pane's tab: its window is in front
     *                        and the tab is selected
     * @param codingAgentPane whether a coding agent was detected in the pane
     * @param agentRun        whether a korTTY terminal-agent run drives the pane, typing its
     *                        commands
     * @param mirroredInput   whether keys typed in another pane reached this one through broadcast
     *                        mode or multi-exec within {@link #MIRRORED_ECHO_WINDOW}, so what it
     *                        prints now is most likely its answer to them
     */
    public record PaneState(boolean seen, boolean codingAgentPane, boolean agentRun, boolean mirroredInput) {

        /** A pane that got no mirrored keys lately. */
        public PaneState(boolean seen, boolean codingAgentPane, boolean agentRun) {
            this(seen, codingAgentPane, agentRun, false);
        }
    }

    /**
     * The run a finished command belongs to in a pane that takes part in multi-exec. What is typed in
     * one member goes to every member, so a command line starts in all of them within moments of each
     * other: commands of the same session whose {@code C} marks lie less than
     * {@link #RUN_START_WINDOW} apart are one run, and a run notifies once.
     *
     * @param session    the multi-exec session the pane takes part in; compared by identity and kept
     *                   weakly, it is the notification slot of every member instead of their tabs
     * @param startNanos {@link System#nanoTime()} at the command's {@code C} mark
     *                   ({@link CommandStatus#outputStartNanos()})
     */
    public record MultiExecRun(Object session, long startNanos) {

        public MultiExecRun {
            Objects.requireNonNull(session, "session");
        }
    }

    /**
     * How long after a mirrored key a pane's bell is taken as its answer to that key
     * ({@link PaneState#mirroredInput()}): a round trip to a slow server and back.
     */
    public static final Duration MIRRORED_ECHO_WINDOW = Duration.ofSeconds(2);

    /**
     * How far apart the {@code C} marks of the same command line typed into a multi-exec session may
     * lie in its members ({@link MultiExecRun}): the keys reach every member in the background, and a
     * slow server starts the command later. A long command keeps its shell busy, so two different
     * runs of one session cannot start this close together in the same panes.
     */
    public static final Duration RUN_START_WINDOW = Duration.ofSeconds(5);

    /** How many notified runs a multi-exec session remembers, so a late member of one is still known. */
    static final int REMEMBERED_RUNS = 16;

    /**
     * The settings a decision depends on, read when the pane asks.
     *
     * @param bellToasts               desktop notifications for the bell
     *                                 ({@code GlobalSettings.terminalBellNotificationsEnabled})
     * @param codingAgentNotifications the coding agents' own desktop notifications
     *                                 ({@code GlobalSettings.codingAgentNotificationsEnabled})
     * @param commandFinishedToasts    desktop notifications for long commands
     *                                 ({@code GlobalSettings.commandFinishedNotificationsEnabled})
     * @param commandFinishedSeconds   how long a command has to run before its end counts, in
     *                                 seconds ({@code GlobalSettings.commandFinishedNotificationSeconds});
     *                                 clamped to 1..3600 ({@link TerminalNotificationPolicy#clampCommandFinishedSeconds})
     * @param remoteToasts             desktop notifications that programs ask for with OSC 9 or OSC 777
     *                                 ({@code GlobalSettings.remoteTerminalNotificationsEnabled})
     */
    public record Toggles(boolean bellToasts, boolean codingAgentNotifications, boolean commandFinishedToasts,
            int commandFinishedSeconds, boolean remoteToasts) {

        public Toggles {
            commandFinishedSeconds = clampCommandFinishedSeconds(commandFinishedSeconds);
        }

        /** {@link #commandFinishedSeconds()} as a duration. */
        public Duration commandFinishedThreshold() {
            return Duration.ofSeconds(commandFinishedSeconds);
        }
    }

    private final LongSupplier clockMillis;

    private final Map<Kind, Map<Object, Long>> lastToastMillis = new EnumMap<>(Kind.class);

    /** Per slot (pane or multi-exec session) and highlight rule id, when the rule last notified. */
    private final Map<Object, Map<String, Long>> lastTriggerToastMillis = new WeakHashMap<>();

    /** The {@code C} marks of the runs each multi-exec session notified about, newest last. */
    private final Map<Object, Deque<Long>> notifiedRunStarts = new WeakHashMap<>();

    /** A policy on the monotonic clock. */
    public TerminalNotificationPolicy() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    /** @param clockMillis a clock in milliseconds that never goes back, such as {@code System.nanoTime() / 1e6} */
    public TerminalNotificationPolicy(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** {@code seconds} within {@value #MIN_COMMAND_FINISHED_SECONDS}..{@value #MAX_COMMAND_FINISHED_SECONDS}. */
    public static int clampCommandFinishedSeconds(int seconds) {
        return Math.max(MIN_COMMAND_FINISHED_SECONDS, Math.min(MAX_COMMAND_FINISHED_SECONDS, seconds));
    }

    /**
     * Whether a command that ran {@code runtime} can ever lead to anything, whatever the threshold
     * is set to: false below {@value #MIN_COMMAND_FINISHED_SECONDS} second. Lets the emulator thread
     * drop short commands before handing anything to the UI thread.
     */
    public static boolean mayNotify(Duration runtime) {
        return runtime != null && runtime.compareTo(Duration.ofSeconds(MIN_COMMAND_FINISHED_SECONDS)) >= 0;
    }

    /**
     * Decides about one request of a kind that needs nothing but the pane, {@link Kind#BELL},
     * {@link Kind#REMOTE}, {@link Kind#ACTIVITY} or {@link Kind#SILENCE}; the pane is its own
     * notification slot. A decision with a toast counts as the slot's last notification of this kind,
     * so call it only when the notification will really be shown. A bell or activity in a pane that
     * got mirrored keys moments ago leads to nothing ({@link PaneState#mirroredInput()}). Activity and
     * silence need no setting: the user switched their watch on for the tab.
     *
     * @param kind    why the pane asks; not {@link Kind#COMMAND_FINISHED}, which has
     *                {@link #decideCommandFinished}
     * @param pane    the notification slot, kept weakly: the pane, a terminal widget compared by
     *                identity, or for a bell, activity or silence the multi-exec session the pane
     *                takes part in
     * @param state   what is known about the pane now
     * @param toggles the settings now
     * @throws IllegalArgumentException for {@link Kind#COMMAND_FINISHED}
     */
    public Decision decide(Kind kind, Object pane, PaneState state, Toggles toggles) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toggles, "toggles");
        boolean toastEnabled = switch (kind) {
            case BELL -> toggles.bellToasts();
            case REMOTE -> toggles.remoteToasts();
            case ACTIVITY, SILENCE -> true;
            case COMMAND_FINISHED -> throw new IllegalArgumentException(
                "A finished command needs its runtime and its tab: use decideCommandFinished");
            case TRIGGER -> throw new IllegalArgumentException(
                "A highlight trigger needs its rule: use decideTrigger");
        };
        if (kind.discountsMirroredInput() && state.mirroredInput()) {
            return Decision.NONE;
        }
        return decide(kind, pane, state, toggles, toastEnabled);
    }

    /**
     * Decides about a command the shell marked finished ({@code OSC 133;D}) after running
     * {@code runtime} from its {@code C} mark.
     *
     * <ul>
     *   <li>Nothing for a command shorter than the threshold
     *       ({@link Toggles#commandFinishedSeconds()}), and nothing for one a terminal-agent run typed
     *       ({@link PaneState#agentRun()}).</li>
     *   <li>Otherwise as every kind: nothing in a seen tab, else the mark, and a notification with
     *       its setting on.</li>
     *   <li>The notification slot is {@code tab}: when several panes of a tab finish within
     *       {@link Kind#COMMAND_FINISHED}'s interval, typically the same command typed into all of
     *       them through broadcast, only the first one notifies; every one keeps the tab marked.</li>
     *   <li>A coding agent in the pane does not matter: the agent itself is the command, and its
     *       end is worth knowing.</li>
     * </ul>
     *
     * @param tab     the pane's tab, the notification slot; kept weakly
     * @param runtime the command's runtime from {@code C} to {@code D}
     * @param state   what is known about the pane now
     * @param toggles the settings now
     */
    public Decision decideCommandFinished(Object tab, Duration runtime, PaneState state, Toggles toggles) {
        return decideCommandFinished(tab, null, runtime, state, toggles);
    }

    /**
     * Decides about a finished command as {@link #decideCommandFinished(Object, Duration, PaneState,
     * Toggles)} does, for a pane that may take part in multi-exec.
     *
     * <ul>
     *   <li>Without a {@code run} the tab is the notification slot, as there.</li>
     *   <li>With one, the slot is the run's multi-exec session, shared by every member whichever tab
     *       holds it, and a run notifies once: a member whose command started within
     *       {@link #RUN_START_WINDOW} of a run the session already notified about only marks its tab,
     *       however long after the first member it finishes. The session's slot keeps the interval
     *       of {@link Kind#COMMAND_FINISHED} between two notifications, too.</li>
     *   <li>A seen tab or a notification switched off takes no slot and marks no run, so a member
     *       finishing later in a tab the user does not look at still notifies.</li>
     * </ul>
     *
     * @param run the multi-exec run the command belongs to, or {@code null} when the pane takes part
     *            in no multi-exec session
     */
    public Decision decideCommandFinished(Object tab, @Nullable MultiExecRun run, Duration runtime, PaneState state,
            Toggles toggles) {
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toggles, "toggles");
        if (state.agentRun() || runtime.compareTo(toggles.commandFinishedThreshold()) < 0) {
            return Decision.NONE;
        }
        if (run == null) {
            return decide(Kind.COMMAND_FINISHED, tab, state, toggles, toggles.commandFinishedToasts());
        }
        if (state.seen()) {
            return Decision.NONE;
        }
        boolean toast = toggles.commandFinishedToasts() && !notifiedAbout(run)
            && claimToast(Kind.COMMAND_FINISHED, run.session());
        if (toast) {
            rememberNotified(run);
        }
        return new Decision(true, toast);
    }

    /**
     * Decides about a highlight rule with the notification action that matched new output
     * ({@link Kind#TRIGGER}).
     *
     * <ul>
     *   <li>Nothing in a tab the user is looking at, and nothing while mirrored keys reached the pane
     *       moments ago ({@link PaneState#mirroredInput()}): the line most likely echoes them.</li>
     *   <li>Otherwise the tab is marked, and a desktop notification comes at most once per rule and
     *       slot within {@link Kind#TRIGGER}'s interval; the slot is the pane, or for a member of
     *       multi-exec its session, so one error printed by every member notifies once.</li>
     * </ul>
     *
     * @param pane   the notification slot, kept weakly: the pane, or the multi-exec session it takes
     *               part in
     * @param ruleId the stable id of the rule that matched
     * @param state  what is known about the pane now
     */
    public Decision decideTrigger(Object pane, String ruleId, PaneState state) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(state, "state");
        if (state.seen() || (Kind.TRIGGER.discountsMirroredInput() && state.mirroredInput())) {
            return Decision.NONE;
        }
        Map<String, Long> last = lastTriggerToastMillis.computeIfAbsent(pane, unused -> new java.util.HashMap<>());
        long now = clockMillis.getAsLong();
        Long previous = last.get(ruleId);
        boolean toast = previous == null || now - previous >= Kind.TRIGGER.toastIntervalMillis();
        if (toast) {
            last.put(ruleId, now);
        }
        return new Decision(true, toast);
    }

    /** Whether the run's session notified about a run whose {@code C} mark lies close to this one's. */
    private boolean notifiedAbout(MultiExecRun run) {
        Deque<Long> starts = notifiedRunStarts.get(run.session());
        if (starts == null) {
            return false;
        }
        long window = RUN_START_WINDOW.toNanos();
        for (long start : starts) {
            if (Math.abs(run.startNanos() - start) < window) {
                return true;
            }
        }
        return false;
    }

    private void rememberNotified(MultiExecRun run) {
        Deque<Long> starts = notifiedRunStarts.computeIfAbsent(run.session(), unused -> new ArrayDeque<>());
        starts.addLast(run.startNanos());
        while (starts.size() > REMEMBERED_RUNS) {
            starts.removeFirst();
        }
    }

    private Decision decide(Kind kind, Object slot, PaneState state, Toggles toggles, boolean toastEnabled) {
        if (state.seen()) {
            return Decision.NONE;
        }
        boolean toast = toastEnabled;
        if (toast && kind.leftToCodingAgents() && state.codingAgentPane() && toggles.codingAgentNotifications()) {
            toast = false;
        }
        return new Decision(true, toast && claimToast(kind, slot));
    }

    /** Takes the notification slot of {@code kind} when its interval has passed. */
    private boolean claimToast(Kind kind, Object slot) {
        Map<Object, Long> last = lastToastMillis.computeIfAbsent(kind, unused -> new WeakHashMap<>());
        long now = clockMillis.getAsLong();
        Long previous = last.get(slot);
        if (previous != null && now - previous < kind.toastIntervalMillis()) {
            return false;
        }
        last.put(slot, now);
        return true;
    }
}
