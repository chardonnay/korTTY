package de.kortty.control;

import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded event fan-out behind {@code events.subscribe}.
 *
 * <p>{@link #publish(ControlEvent)} is called from the registry listener, i.e. <strong>on the JavaFX
 * application thread</strong>. It therefore does nothing but offer one frame per matching
 * subscription and hand the delivery to the timer: it must never throw and never block, because a
 * slow socket reader must not be able to stall the toolkit.
 *
 * <p>A full per-subscription queue drops the <em>oldest</em> frames and emits a single
 * {@code events.overflow} carrying {@code dropped=N}, so a client that falls behind loses the stalest
 * history and still learns exactly how much it lost.
 *
 * <p>Everything else is any thread, never the JavaFX application thread.
 */
public final class ControlEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(ControlEventBus.class);

    /** The wire method every event notification carries. */
    private static final String EVENT_METHOD = "event";

    /** The kind the bus itself emits when frames were dropped. */
    private static final String KIND_OVERFLOW = "events.overflow";

    /** The opt-in kind that carries a changed detection evidence line. */
    private static final String KIND_EVIDENCE = "agent.evidence";

    /** The kinds a subscription gets when it names none. */
    private static final Set<String> DEFAULT_KINDS = defaultKinds();

    private final ScheduledExecutorService timer;

    private final LongSupplier clockMillis;

    private final Map<String, Registration> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    /** The last moment an {@code agent.evidence} frame was published, per pane. */
    private final Map<String, Long> evidenceGate = new ConcurrentHashMap<>();

    /**
     * @param timer the shared timer executor that drains the per-subscription queues
     * @param clockMillis the wall clock, injected so tests never sleep
     */
    public ControlEventBus(ScheduledExecutorService timer, LongSupplier clockMillis) {
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** One client's live interest in events; closing it stops delivery. */
    public interface Subscription extends AutoCloseable {

        /** The id {@code events.unsubscribe} takes. */
        String id();

        @Override
        void close();
    }

    /**
     * Registers one interest.
     *
     * @param session the connection that receives the frames
     * @param kinds the kinds to receive; null or empty means everything except {@code agent.evidence}
     * @param paneIds the panes to receive events for; null or empty means every pane
     * @param includeEvidence whether {@code agent.evidence} may be delivered at all
     */
    public Subscription subscribe(ControlSession session, Set<String> kinds, Set<String> paneIds,
                                  boolean includeEvidence) {
        Objects.requireNonNull(session, "session");
        long order = sequence.incrementAndGet();
        String id = "s" + order;
        Set<String> wanted = kinds == null || kinds.isEmpty()
            ? DEFAULT_KINDS
            : Set.copyOf(kinds);
        Set<String> panes = paneIds == null || paneIds.isEmpty() ? Set.of() : Set.copyOf(paneIds);
        Registration registration =
            new Registration(id, order, session, wanted, panes, includeEvidence);
        subscriptions.put(id, registration);
        return registration;
    }

    /** Drops one subscription; unknown ids are ignored. */
    public void unsubscribe(String subscriptionId) {
        if (subscriptionId == null) {
            return;
        }
        Registration registration = subscriptions.remove(subscriptionId);
        if (registration != null) {
            registration.detach();
        }
    }

    /**
     * Drops one subscription, but only when it belongs to the given connection, so a client can never
     * silence another client's stream by guessing an id.
     *
     * @return whether a subscription of that connection was dropped
     */
    public boolean unsubscribe(String connectionId, String subscriptionId) {
        if (connectionId == null || subscriptionId == null) {
            return false;
        }
        Registration registration = subscriptions.get(subscriptionId);
        if (registration == null || !connectionId.equals(registration.connectionId())) {
            return false;
        }
        if (!subscriptions.remove(subscriptionId, registration)) {
            return false;
        }
        registration.detach();
        return true;
    }

    /**
     * The live subscription ids of one connection, oldest first.
     *
     * <p>This is the bus's own answer to "what is this connection still subscribed to", so no verb has
     * to keep a second, never-pruned index of its own.
     */
    public List<String> subscriptionsOf(String connectionId) {
        List<Registration> owned = ownedBy(connectionId);
        owned.sort(Comparator.comparingLong(Registration::sequence));
        List<String> ids = new ArrayList<>(owned.size());
        for (Registration registration : owned) {
            ids.add(registration.id());
        }
        return List.copyOf(ids);
    }

    /**
     * Drops every subscription of one connection.
     *
     * <p>Called when a control connection closes, however it closed — {@code events.unsubscribe}, a
     * client that simply went away, an overflowing outbound queue. Without it a dead connection's
     * registrations stay {@code open} and every registry change keeps copying JSON and scheduling
     * drains for them on the JavaFX thread for the life of the process.
     *
     * @return how many subscriptions were dropped
     */
    public int closeConnection(String connectionId) {
        int closed = 0;
        for (Registration registration : ownedBy(connectionId)) {
            if (subscriptions.remove(registration.id(), registration)) {
                registration.detach();
                closed++;
            }
        }
        return closed;
    }

    private List<Registration> ownedBy(String connectionId) {
        List<Registration> owned = new ArrayList<>();
        if (connectionId == null) {
            return owned;
        }
        for (Registration registration : subscriptions.values()) {
            if (connectionId.equals(registration.connectionId())) {
                owned.add(registration);
            }
        }
        return owned;
    }

    /**
     * Offers one event to every matching subscription. Called on the JavaFX application thread; it
     * never throws and never blocks.
     */
    public void publish(ControlEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (KIND_EVIDENCE.equals(event.kind()) && !evidenceAllowed(event.paneId())) {
                return;
            }
            JsonObject params = ControlJson.toTree(event).getAsJsonObject();
            for (Registration registration : subscriptions.values()) {
                if (registration.wants(event)) {
                    registration.offer(ControlFrame.event(EVENT_METHOD, params.deepCopy()));
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("control-api dropped an event it could not publish: {}", e.toString());
        }
    }

    /**
     * The adapter registered with {@code CodingAgentRegistry.addListener} by the UI bridge.
     *
     * <p>{@code EVIDENCE_CHANGED} is translated into the distinct kind {@code agent.evidence} here, at
     * the FX boundary, and is <strong>never</strong> published as {@code agent.state_changed}: a
     * WORKING agent emits one every 200 ms coalescing window and would otherwise flood the socket.
     */
    public CodingAgentRegistry.Listener registryListener(ControlSurface surface) {
        return change -> {
            try {
                ControlEvent event = toEvent(change, surface);
                if (event != null) {
                    publish(event);
                }
            } catch (RuntimeException e) {
                LOG.debug("control-api could not translate a registry change: {}", e.toString());
            }
        };
    }

    /** Drops every subscription, for example when the server shuts down. */
    public void closeAll() {
        List<Registration> live = new ArrayList<>(subscriptions.values());
        subscriptions.clear();
        for (Registration registration : live) {
            registration.detach();
        }
        evidenceGate.clear();
    }

    private boolean evidenceAllowed(String paneId) {
        String key = paneId == null ? "" : paneId;
        long now = clockMillis.getAsLong();
        Long last = evidenceGate.get(key);
        if (last != null && now - last < ControlApiProtocol.EVIDENCE_MIN_INTERVAL_MILLIS) {
            return false;
        }
        evidenceGate.put(key, now);
        return true;
    }

    private ControlEvent toEvent(RegistryChange change, ControlSurface surface) {
        if (change == null || change.pane() == null) {
            return null;
        }
        String kind = kindOf(change.kind());
        if (kind == null) {
            return null;
        }
        PaneRef ref = change.pane();
        String paneId = ControlIds.paneIdFromWidgetPaneId(ref.paneId());
        String tabId = ControlIds.tabId(ref.tabId());
        String windowId = windowIdOf(surface, paneId);
        CodingAgentEntry current = change.current();
        AgentInfo agent = current == null ? null
            : AgentInfo.of(current, paneId, tabId, windowId, change.atMillis());
        String previous = change.previous() == null ? null : wire(change.previous().state());
        return new ControlEvent(kind, change.atMillis(), windowId, tabId, paneId, agent, previous, null);
    }

    /** Best effort: an event must be published even when the pane has already gone. */
    private static String windowIdOf(ControlSurface surface, String paneId) {
        if (surface == null) {
            return null;
        }
        try {
            return surface.resolve(PaneAddress.ofPaneId(paneId)).windowId();
        } catch (ControlApiException | RuntimeException e) {
            return null;
        }
    }

    private static String kindOf(RegistryChange.Kind kind) {
        return switch (kind) {
            case ADDED -> "agent.added";
            case STATE_CHANGED -> "agent.state_changed";
            case EVIDENCE_CHANGED -> KIND_EVIDENCE;
            case SEEN -> "agent.seen";
            case ALIAS_CHANGED -> "agent.alias_changed";
            case REMOVED -> "agent.removed";
        };
    }

    private static String wire(CodingAgentState state) {
        return state == null ? "unknown" : state.name().toLowerCase(Locale.ROOT);
    }

    private static Set<String> defaultKinds() {
        Set<String> kinds = new LinkedHashSet<>(ControlEvent.KINDS);
        kinds.remove(KIND_EVIDENCE);
        return Set.copyOf(kinds);
    }

    /** One live subscription: a bounded queue plus the single drain that owns it. */
    private final class Registration implements Subscription {

        private final String id;

        /** The mint order behind {@link #id}, so a connection's subscriptions list oldest first. */
        private final long sequence;

        private final ControlSession session;

        private final Set<String> kinds;

        private final Set<String> paneIds;

        private final boolean includeEvidence;

        private final ArrayBlockingQueue<ControlFrame> queue =
            new ArrayBlockingQueue<>(ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);

        private final AtomicInteger dropped = new AtomicInteger();

        private final AtomicBoolean draining = new AtomicBoolean();

        private volatile boolean open = true;

        private Registration(String id, long sequence, ControlSession session, Set<String> kinds,
                             Set<String> paneIds, boolean includeEvidence) {
            this.id = id;
            this.sequence = sequence;
            this.session = session;
            this.kinds = kinds;
            this.paneIds = paneIds;
            this.includeEvidence = includeEvidence;
        }

        @Override
        public String id() {
            return id;
        }

        private long sequence() {
            return sequence;
        }

        /** The connection this subscription belongs to, which is what its lifetime is tied to. */
        private String connectionId() {
            return session.connectionId();
        }

        @Override
        public void close() {
            unsubscribe(id);
        }

        private void detach() {
            open = false;
            queue.clear();
        }

        private boolean wants(ControlEvent event) {
            if (!open) {
                return false;
            }
            if (KIND_EVIDENCE.equals(event.kind()) && !includeEvidence) {
                return false;
            }
            if (!kinds.contains(event.kind()) && !KIND_OVERFLOW.equals(event.kind())) {
                return false;
            }
            return paneIds.isEmpty() || event.paneId() == null || paneIds.contains(event.paneId());
        }

        /** Never blocks: a full queue loses its oldest frame and the loss is counted. */
        private void offer(ControlFrame frame) {
            while (!queue.offer(frame)) {
                if (queue.poll() == null) {
                    return;
                }
                dropped.incrementAndGet();
            }
            schedule();
        }

        private void schedule() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                timer.execute(this::drain);
            } catch (RuntimeException e) {
                draining.set(false);
                LOG.debug("control-api could not schedule an event drain for {}: {}", id, e.toString());
            }
        }

        private void drain() {
            try {
                ControlFrame frame;
                while ((frame = queue.poll()) != null) {
                    deliver(frame);
                }
                int lost = dropped.getAndSet(0);
                if (lost > 0) {
                    deliver(ControlFrame.event(EVENT_METHOD, overflow(lost)));
                }
            } finally {
                draining.set(false);
                if (!queue.isEmpty() || dropped.get() > 0) {
                    schedule();
                }
            }
        }

        private void deliver(ControlFrame frame) {
            if (!open) {
                return;
            }
            try {
                session.notifier().accept(frame);
            } catch (RuntimeException e) {
                LOG.debug("control-api subscriber {} refused a frame: {}", id, e.toString());
            }
        }

        private JsonObject overflow(int lost) {
            JsonObject params = new JsonObject();
            params.addProperty("kind", KIND_OVERFLOW);
            params.addProperty("at_millis", clockMillis.getAsLong());
            params.addProperty("dropped", lost);
            params.addProperty("subscription_id", id);
            return params;
        }
    }
}
