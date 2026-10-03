package de.kortty.shellintegration;

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
 *   <li>A desktop notification needs its setting on, and comes at most once per pane within the
 *       kind's interval ({@link Kind#toastIntervalMillis()}); a request inside the interval only
 *       marks the tab.</li>
 *   <li>No desktop notification for a pane in which a coding agent was detected while the
 *       coding-agent notifications are on: the agent rings the bell when it waits for a decision,
 *       and its own notification already says so.</li>
 * </ul>
 *
 * <p>FX-free and clock-injected. Not thread-safe: call it from one thread, the UI thread in the
 * application. Panes are kept weakly, so a closed pane needs no clean-up.
 */
public final class TerminalNotificationPolicy {

    /** Why a pane asks for attention. */
    public enum Kind {
        /** A program in the pane rang the terminal bell (BEL). */
        BELL(10_000L);

        private final long toastIntervalMillis;

        Kind(long toastIntervalMillis) {
            this.toastIntervalMillis = toastIntervalMillis;
        }

        /** The shortest time between two desktop notifications of this kind for the same pane. */
        public long toastIntervalMillis() {
            return toastIntervalMillis;
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
     */
    public record PaneState(boolean seen, boolean codingAgentPane) {
    }

    /**
     * The settings a decision depends on, read when the pane asks.
     *
     * @param bellToasts               desktop notifications for the bell
     *                                 ({@code GlobalSettings.terminalBellNotificationsEnabled})
     * @param codingAgentNotifications the coding agents' own desktop notifications
     *                                 ({@code GlobalSettings.codingAgentNotificationsEnabled})
     */
    public record Toggles(boolean bellToasts, boolean codingAgentNotifications) {
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

    /**
     * Decides about one request. A decision with a toast counts as the pane's last notification of
     * this kind, so call it only when the notification will really be shown.
     *
     * @param kind    why the pane asks
     * @param pane    the pane, kept weakly; a terminal widget compares by identity
     * @param state   what is known about the pane now
     * @param toggles the settings now
     */
    public Decision decide(Kind kind, Object pane, PaneState state, Toggles toggles) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toggles, "toggles");
        if (state.seen()) {
            return Decision.NONE;
        }
        boolean toast = switch (kind) {
            case BELL -> toggles.bellToasts();
        };
        if (toast && state.codingAgentPane() && toggles.codingAgentNotifications()) {
            toast = false;
        }
        return new Decision(true, toast && claimToast(kind, pane));
    }

    /** Takes the pane's notification slot of {@code kind} when its interval has passed. */
    private boolean claimToast(Kind kind, Object pane) {
        Map<Object, Long> last = lastToastMillis.computeIfAbsent(kind, unused -> new WeakHashMap<>());
        long now = clockMillis.getAsLong();
        Long previous = last.get(pane);
        if (previous != null && now - previous < kind.toastIntervalMillis()) {
            return false;
        }
        last.put(pane, now);
        return true;
    }
}
