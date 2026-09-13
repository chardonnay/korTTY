package de.kortty.control;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code events.subscribe} and {@code events.unsubscribe}.
 *
 * <p>Omitting {@code kinds} subscribes to everything <strong>except</strong> {@code agent.evidence}:
 * a WORKING agent emits one of those every 200 ms coalescing window, so it is opt-in and
 * rate-limited by {@link ControlEventBus}.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class EventVerbs {

    /** The parameter naming the subscription to drop. */
    private static final String PARAM_SUBSCRIPTION = "subscription_id";

    private EventVerbs() {
    }

    /**
     * Registers the event surface.
     *
     * @param builder the table being assembled
     * @param events the fan-out
     */
    static void register(MethodRegistry.Builder builder, ControlEventBus events) {
        // Per connection, so events.unsubscribe without an id can drop exactly this client's own.
        Map<String, List<ControlEventBus.Subscription>> perConnection = new ConcurrentHashMap<>();

        builder.register(new MethodSpec("events.subscribe",
                "Starts pushing agent events to this connection.",
                List.of(new ParamSpec("kinds", "string[]", false, null,
                        "Event kinds; omit for everything except agent.evidence."),
                    new ParamSpec("panes", "string[]", false, null,
                        "Pane ids; omit for every pane."),
                    new ParamSpec("include_evidence", "bool", false, "false",
                        "Allow the rate-limited agent.evidence kind.")),
                "{subscription_id, kinds, queue_depth}",
                List.of(ControlErrorCode.INVALID_PARAMS), false, false,
                "kortty-cli events watch",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"events.subscribe\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"subscription_id\":\"s1\"}}"),
            (session, params) -> {
                Set<String> kinds = kinds(params);
                Set<String> panes = new LinkedHashSet<>(ControlJson.optStringList(params, "panes"));
                boolean includeEvidence = ControlJson.optBool(params, "include_evidence", false);
                ControlEventBus.Subscription subscription =
                    events.subscribe(session, kinds, panes, includeEvidence);
                perConnection.computeIfAbsent(session.connectionId(), key -> new ArrayList<>())
                    .add(subscription);
                JsonObject result = new JsonObject();
                result.addProperty(PARAM_SUBSCRIPTION, subscription.id());
                result.add("kinds", BaseVerbs.tree(
                    kinds.isEmpty() ? defaultKinds(includeEvidence) : List.copyOf(kinds)));
                result.addProperty("queue_depth", ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);
                return result;
            });

        builder.register(new MethodSpec("events.unsubscribe",
                "Stops one subscription, or every subscription of this connection.",
                List.of(new ParamSpec(PARAM_SUBSCRIPTION, "string", false, null,
                    "Omit to drop all of this connection's subscriptions.")),
                "{subscribed:[string]}",
                List.of(ControlErrorCode.INVALID_PARAMS), false, false,
                "kortty-cli events unwatch",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"events.unsubscribe\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"subscribed\":[]}}"),
            (session, params) -> {
                String id = ControlJson.optString(params, PARAM_SUBSCRIPTION, null);
                List<ControlEventBus.Subscription> live =
                    perConnection.computeIfAbsent(session.connectionId(), key -> new ArrayList<>());
                synchronized (live) {
                    if (id == null) {
                        for (ControlEventBus.Subscription subscription : live) {
                            subscription.close();
                        }
                        live.clear();
                    } else {
                        live.removeIf(subscription -> {
                            if (!subscription.id().equals(id)) {
                                return false;
                            }
                            subscription.close();
                            return true;
                        });
                    }
                    JsonObject result = new JsonObject();
                    List<String> remaining = new ArrayList<>();
                    for (ControlEventBus.Subscription subscription : live) {
                        remaining.add(subscription.id());
                    }
                    result.add("subscribed", BaseVerbs.tree(remaining));
                    return result;
                }
            });
    }

    private static Set<String> kinds(JsonObject params) throws ControlApiException {
        List<String> requested = ControlJson.optStringList(params, "kinds");
        Set<String> kinds = new LinkedHashSet<>();
        for (String kind : requested) {
            if (!ControlEvent.KINDS.contains(kind)) {
                throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                    "Unknown event kind: " + kind,
                    Map.of("param", "kinds", "kind", kind, "known", List.copyOf(ControlEvent.KINDS)));
            }
            kinds.add(kind);
        }
        return kinds;
    }

    /** What an empty {@code kinds} list actually subscribes to, echoed back to the client. */
    private static List<String> defaultKinds(boolean includeEvidence) {
        List<String> kinds = new ArrayList<>(ControlEvent.KINDS);
        if (!includeEvidence) {
            kinds.remove("agent.evidence");
        }
        kinds.sort(null);
        return List.copyOf(kinds);
    }
}
