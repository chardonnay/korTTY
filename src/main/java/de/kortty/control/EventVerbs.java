package de.kortty.control;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // The bus itself indexes a subscription by its connection, so there is no second map here to
        // grow for the life of the process: every connection id is minted once and never reused.
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
                "kortty-cli events --kinds agent.state_changed",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"events.subscribe\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"subscription_id\":\"s1\"}}"),
            (session, params) -> {
                Set<String> kinds = kinds(params);
                Set<String> panes = new LinkedHashSet<>(ControlJson.optStringList(params, "panes"));
                boolean includeEvidence = ControlJson.optBool(params, "include_evidence", false);
                ControlEventBus.Subscription subscription =
                    events.subscribe(session, kinds, panes, includeEvidence);
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
                "kortty-cli raw events.unsubscribe {\"subscription\":\"<id>\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"events.unsubscribe\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"subscribed\":[]}}"),
            (session, params) -> {
                String id = ControlJson.optString(params, PARAM_SUBSCRIPTION, null);
                if (id == null) {
                    events.closeConnection(session.connectionId());
                } else {
                    // Scoped to this connection: one client must never be able to silence another's
                    // stream by guessing an id.
                    events.unsubscribe(session.connectionId(), id);
                }
                JsonObject result = new JsonObject();
                result.add("subscribed", BaseVerbs.tree(events.subscriptionsOf(session.connectionId())));
                return result;
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
