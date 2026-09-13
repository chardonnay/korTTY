package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides when a registry change becomes a desktop notification. Pure logic driven by
 * {@link #onRegistryChanged} and a periodic {@link #tick()} (the bridge's 1-s timeline) with an
 * injected clock:
 * <ul>
 *   <li>a transition to BLOCKED is delivered once it has settled for {@link #SETTLE_MILLIS} and
 *       the entry is still BLOCKED with the same {@code stateSinceMillis};</li>
 *   <li>a transition to DONE is delivered on the next tick, but only for the registry's
 *       done-until-seen DONE (a rule-detected DONE is not a "finished while you were away");</li>
 *   <li>nothing is delivered for a pane the user is looking at, while notifications are disabled,
 *       twice for the same (pane, state, stateSince), or more often than
 *       {@link #MIN_INTERVAL_PER_PANE_MILLIS} per pane (such a delivery waits for the interval).</li>
 * </ul>
 * The coordinator does not register itself with the registry; the bridge forwards changes.
 */
public final class CodingAgentNotificationCoordinator implements CodingAgentRegistry.Listener {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentNotificationCoordinator.class);

    /** Receives the notifications that passed the policy. */
    public interface Sink {
        void notify(CodingAgentEntry entry, CodingAgentState state, boolean anyWindowFocused);
    }

    public static final long SETTLE_MILLIS = 800L;
    public static final long MIN_INTERVAL_PER_PANE_MILLIS = 10_000L;
    /** Value of {@code lastDeliveredMillis} meaning "never delivered for this pane". */
    public static final long NEVER_DELIVERED = Long.MIN_VALUE;

    private record Pending(CodingAgentState state, long stateSince, long dueAt) {}

    private record Delivered(CodingAgentState state, long stateSince, long atMillis) {}

    private final CodingAgentRegistry registry;
    private final FocusOracle focus;
    private final BooleanSupplier enabled;
    private final Sink sink;
    private final LongSupplier clockMillis;
    private final LinkedHashMap<PaneRef, Pending> pending = new LinkedHashMap<>();
    private final HashMap<PaneRef, Delivered> delivered = new HashMap<>();

    public CodingAgentNotificationCoordinator(CodingAgentRegistry registry, FocusOracle focus, BooleanSupplier enabled,
                                              Sink sink, LongSupplier clockMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.focus = focus == null ? FocusOracle.NEVER : focus;
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    @Override
    public void onRegistryChanged(RegistryChange change) {
        if (change == null) {
            return;
        }
        PaneRef pane = change.pane();
        if (change.kind() == RegistryChange.Kind.REMOVED) {
            pending.remove(pane);
            delivered.remove(pane);
            return;
        }
        CodingAgentEntry current = change.current();
        if (current == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (change.entered(CodingAgentState.BLOCKED)) {
            pending.put(pane, new Pending(CodingAgentState.BLOCKED, current.stateSinceMillis(), now + SETTLE_MILLIS));
        } else if (change.entered(CodingAgentState.DONE) && current.doneUntilSeen()) {
            pending.put(pane, new Pending(CodingAgentState.DONE, current.stateSinceMillis(), now));
        } else {
            Pending queued = pending.get(pane);
            if (queued != null && (queued.state() != current.state()
                    || queued.stateSince() != current.stateSinceMillis())) {
                pending.remove(pane);
            }
        }
    }

    /** Delivers every settled pending notification that still passes the policy. */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = clockMillis.getAsLong();
        List<Map.Entry<PaneRef, Pending>> due = new ArrayList<>();
        for (Map.Entry<PaneRef, Pending> item : pending.entrySet()) {
            if (item.getValue().dueAt() <= now) {
                due.add(item);
            }
        }
        for (Map.Entry<PaneRef, Pending> item : due) {
            PaneRef pane = item.getKey();
            Pending queued = item.getValue();
            CodingAgentEntry current = registry.entry(pane).orElse(null);
            boolean stale = current == null || current.state() != queued.state()
                || current.stateSinceMillis() != queued.stateSince();
            Delivered last = delivered.get(pane);
            boolean duplicate = last != null && last.state() == queued.state()
                && last.stateSince() == queued.stateSince();
            boolean seen = focus.isSeen(pane);
            boolean on = enabled.getAsBoolean();
            if (stale || duplicate || seen || !on) {
                pending.remove(pane);
                continue;
            }
            long lastDelivered = last == null ? NEVER_DELIVERED : last.atMillis();
            if (!shouldDeliver(current, queued.state(), queued.stateSince(), false, true, lastDelivered, now)) {
                // Only the per-pane interval is left to wait for; keep it queued.
                continue;
            }
            pending.remove(pane);
            delivered.put(pane, new Delivered(queued.state(), queued.stateSince(), now));
            try {
                sink.notify(current, queued.state(), focus.isAnyWindowFocused());
            } catch (RuntimeException e) {
                logger.warn("Coding agent notification sink failed for {}: {}", pane, e.toString());
            }
        }
        removeStalePending();
    }

    /** Number of queued transitions waiting for settle, interval or the next tick. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * The pure policy: deliver when enabled, the pane is not seen, the entry still is in the queued
     * state with the queued {@code stateSince}, and the previous delivery for the pane
     * ({@link #NEVER_DELIVERED} for none) lies at least {@link #MIN_INTERVAL_PER_PANE_MILLIS} back.
     */
    public static boolean shouldDeliver(CodingAgentEntry current, CodingAgentState queuedState, long queuedStateSince,
                                        boolean seen, boolean enabled, long lastDeliveredMillis, long nowMillis) {
        if (!enabled || seen || current == null || queuedState == null) {
            return false;
        }
        if (current.state() != queuedState || current.stateSinceMillis() != queuedStateSince) {
            return false;
        }
        if (lastDeliveredMillis == NEVER_DELIVERED) {
            return true;
        }
        return nowMillis - lastDeliveredMillis >= MIN_INTERVAL_PER_PANE_MILLIS;
    }

    private void removeStalePending() {
        Iterator<Map.Entry<PaneRef, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PaneRef, Pending> item = iterator.next();
            CodingAgentEntry current = registry.entry(item.getKey()).orElse(null);
            if (current == null || current.state() != item.getValue().state()
                    || current.stateSinceMillis() != item.getValue().stateSince()) {
                iterator.remove();
            }
        }
    }
}
