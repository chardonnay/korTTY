package de.kortty.shellintegration;

import java.time.Duration;
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
 *       interval only marks the tab. The slot is the pane for a bell, and the tab for a finished
 *       command (see {@link #decideCommandFinished}).</li>
 *   <li>No bell notification for a pane in which a coding agent was detected while the
 *       coding-agent notifications are on: the agent rings the bell when it waits for a decision,
 *       and its own notification already says so.</li>
 * </ul>
 *
 * <p>A finished command ({@link Kind#COMMAND_FINISHED}) also has to have run at least the
 * threshold of the settings, and a command that a korTTY terminal-agent run typed into the pane
 * leads to nothing at all: the run reports its own commands.
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
        BELL(10_000L, true),
        /**
         * A command the shell marked with {@code OSC 133} finished ({@code D}) after running at
         * least the threshold. Its notification slot is the tab, so the same command finishing in
         * several panes that broadcast mirrors into notifies once.
         */
        COMMAND_FINISHED(10_000L, false);

        private final long toastIntervalMillis;
        private final boolean leftToCodingAgents;

        Kind(long toastIntervalMillis, boolean leftToCodingAgents) {
            this.toastIntervalMillis = toastIntervalMillis;
            this.leftToCodingAgents = leftToCodingAgents;
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
     */
    public record PaneState(boolean seen, boolean codingAgentPane, boolean agentRun) {
    }

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
     */
    public record Toggles(boolean bellToasts, boolean codingAgentNotifications, boolean commandFinishedToasts,
            int commandFinishedSeconds) {

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
     * Decides about one request of a kind that needs nothing but the pane, such as
     * {@link Kind#BELL}; the pane is its own notification slot. A decision with a toast counts as the
     * slot's last notification of this kind, so call it only when the notification will really be
     * shown.
     *
     * @param kind    why the pane asks; not {@link Kind#COMMAND_FINISHED}, which has
     *                {@link #decideCommandFinished}
     * @param pane    the pane, kept weakly; a terminal widget compares by identity
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
            case COMMAND_FINISHED -> throw new IllegalArgumentException(
                "A finished command needs its runtime and its tab: use decideCommandFinished");
        };
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
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toggles, "toggles");
        if (state.agentRun() || runtime.compareTo(toggles.commandFinishedThreshold()) < 0) {
            return Decision.NONE;
        }
        return decide(Kind.COMMAND_FINISHED, tab, state, toggles, toggles.commandFinishedToasts());
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
