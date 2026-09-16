package de.kortty.telemetry;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.RegistryChange;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Anonymous usage statistics for coding-agent awareness and the local control API.
 *
 * <p>Deduplicated per app run so a busy agent or a polling script never floods the queue:
 * {@code coding_agent_detected} fires once per agent kind, {@code control_api_used} once per wire
 * method. Notifications are already rate-limited per pane by the coordinator and are tracked each
 * time. Counts for the whole run go into {@code usage_snapshot} via {@link #putSnapshotProps}.
 * Props carry kinds, states, method names and counts only — never terminal text, prompts or aliases.
 *
 * <p>Any thread.
 */
public final class CodingAgentUsage implements CodingAgentRegistry.Listener {

    /** The client name {@code kortty-cli} sends to {@code auth}. */
    static final String CLI_CLIENT = "kortty-cli";

    private static final CodingAgentUsage DEFAULT = new CodingAgentUsage(Telemetry::track);

    private final BiConsumer<String, Map<String, Object>> tracker;
    private final Set<CodingAgentKind> detectedKinds = EnumSet.noneOf(CodingAgentKind.class);
    private final Set<String> usedMethods = ConcurrentHashMap.newKeySet();
    private final AtomicInteger detections = new AtomicInteger();
    private final AtomicInteger notifications = new AtomicInteger();
    private final AtomicInteger controlRequests = new AtomicInteger();

    CodingAgentUsage(BiConsumer<String, Map<String, Object>> tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /** The instance wired to {@link Telemetry}. */
    public static CodingAgentUsage get() {
        return DEFAULT;
    }

    @Override
    public void onRegistryChanged(RegistryChange change) {
        if (change == null || change.kind() != RegistryChange.Kind.ADDED || change.current() == null) {
            return;
        }
        CodingAgentKind kind = change.current().kind();
        if (kind == null) {
            return;
        }
        detections.incrementAndGet();
        boolean first;
        synchronized (detectedKinds) {
            first = detectedKinds.add(kind);
        }
        if (first) {
            tracker.accept(TelemetryEvents.CODING_AGENT_DETECTED, Map.of("kind", kindId(kind)));
        }
    }

    /** A desktop notification about a blocked or finished agent was shown. */
    public void notificationShown(CodingAgentEntry entry, CodingAgentState state) {
        notifications.incrementAndGet();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("state", state == null ? "unknown" : state.name().toLowerCase(Locale.ROOT));
        props.put("kind", entry == null ? "unknown" : kindId(entry.kind()));
        tracker.accept(TelemetryEvents.CODING_AGENT_NOTIFICATION, props);
    }

    /**
     * A control-API request passed authentication and reached a registered method.
     *
     * @param method the wire method name
     * @param client the client name from {@code auth}; reduced to {@code kortty-cli} or {@code other}
     */
    public void controlRequestHandled(String method, String client) {
        if (method == null || method.isBlank()) {
            return;
        }
        controlRequests.incrementAndGet();
        if (usedMethods.add(method)) {
            tracker.accept(TelemetryEvents.CONTROL_API_USED, Map.of(
                "method", method,
                "client", CLI_CLIENT.equals(client) ? CLI_CLIENT : "other"));
        }
    }

    /** Adds the run's coding-agent and control-API counters to a {@code usage_snapshot}. */
    public void putSnapshotProps(Map<String, Object> props) {
        int kinds;
        synchronized (detectedKinds) {
            kinds = detectedKinds.size();
        }
        props.put("coding_agent_detections", detections.get());
        props.put("coding_agent_kinds_detected", kinds);
        props.put("coding_agent_notifications", notifications.get());
        props.put("control_api_requests", controlRequests.get());
        props.put("control_api_methods_used", usedMethods.size());
    }

    private static String kindId(CodingAgentKind kind) {
        if (kind == null || kind.id() == null) {
            return "unknown";
        }
        return kind.id();
    }
}
