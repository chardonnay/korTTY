package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * {@code ping} and {@code api.schema}, plus the parameter helpers every other verb file shares:
 * selector resolution, the optional {@code instance} guard and the one-hop UI wrapper.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class BaseVerbs {

    /** The optional parameter every mutating verb accepts to pin the running korTTY. */
    static final String PARAM_INSTANCE = "instance";

    /** The parameter name every pane selector uses. */
    static final String PARAM_PANE = "pane";

    /** The parameter name every tab selector uses. */
    static final String PARAM_TAB = "tab";

    /** The parameter name every window selector uses. */
    static final String PARAM_WINDOW = "window";

    private BaseVerbs() {
    }

    /**
     * Registers the two discovery verbs.
     *
     * @param builder the table being assembled
     * @param self the registry this build produces, filled in once {@code build()} has run — the
     *     schema must describe the very table that dispatches it, which is only possible by closing
     *     over the reference
     * @param clockMillis the wall clock
     * @param startedAtMillis when this server run began, for {@code ping.uptime_millis}
     * @param appVersion the korTTY version
     * @param instanceId the id minted at server start
     */
    static void register(MethodRegistry.Builder builder, AtomicReference<MethodRegistry> self,
                         LongSupplier clockMillis, long startedAtMillis, String appVersion,
                         String instanceId) {
        builder.register(new MethodSpec("ping",
                "Checks that the control API answers and reports the running instance.",
                List.of(), "{pong, instance, protocol_version, app_version, uptime_millis}",
                List.of(), false, false,
                "kortty-cli ping",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pong\":true}}"),
            (session, params) -> {
                JsonObject result = new JsonObject();
                result.addProperty("pong", true);
                result.addProperty(PARAM_INSTANCE, instanceId);
                result.addProperty("protocol_version", ControlApiProtocol.PROTOCOL_VERSION);
                result.addProperty("app_version", appVersion);
                result.addProperty("uptime_millis",
                    Math.max(0L, clockMillis.getAsLong() - startedAtMillis));
                return result;
            });

        builder.register(new MethodSpec("api.schema",
                "Returns the machine-readable description of every method, error, event and limit.",
                List.of(new ParamSpec("method", "string", false, null,
                    "One method name; omit for the whole document.")),
                "the schema document, or one method entry",
                List.of(ControlErrorCode.UNKNOWN_METHOD, ControlErrorCode.INVALID_PARAMS),
                false, false,
                "kortty-cli api schema",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"api.schema\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"api\":\"kortty-control\"}}"),
            (session, params) -> {
                MethodRegistry table = self.get();
                if (table == null) {
                    throw new ControlApiException(ControlErrorCode.NOT_READY,
                        "The method table is not built yet");
                }
                String method = ControlJson.optString(params, "method", null);
                if (method == null) {
                    return ControlApiSchema.document(table.specs(), table.reserved(), appVersion);
                }
                MethodSpec spec = table.spec(method).orElseThrow(() -> new ControlApiException(
                    ControlErrorCode.UNKNOWN_METHOD, "Unknown method: " + method,
                    Map.of("method", method)));
                return ControlApiSchema.method(spec);
            });
    }

    /**
     * Refuses a mutating call whose optional {@code instance} does not name the running korTTY,
     * instead of writing into whatever pane inherited the id after a restart.
     *
     * @throws ControlApiException {@link ControlErrorCode#STALE_INSTANCE}
     */
    static void requireInstance(JsonObject params, String instanceId) throws ControlApiException {
        String requested = ControlJson.optString(params, PARAM_INSTANCE, null);
        if (requested == null || requested.equals(instanceId)) {
            return;
        }
        throw new ControlApiException(ControlErrorCode.STALE_INSTANCE,
            "This korTTY is a different instance; re-enumerate before addressing it",
            Map.of(PARAM_INSTANCE, instanceId, "requested", requested));
    }

    /** Resolves the required {@code pane} selector to exactly one live pane. */
    static PaneInfo requirePane(ControlSurface surface, UiDispatcher ui, JsonObject params)
            throws ControlApiException {
        PaneAddress address = PaneAddress.parse(ControlJson.requireString(params, PARAM_PANE));
        return inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> surface.resolve(address));
    }

    /**
     * Resolves an optional tab selector — a tab id, the tab part of a qualified address, a pane id or
     * {@code @focused} — to a tab id, or null when the parameter is absent.
     */
    static String optionalTabId(ControlSurface surface, UiDispatcher ui, JsonObject params,
                                String name) throws ControlApiException {
        String selector = ControlJson.optString(params, name, null);
        if (selector == null) {
            return null;
        }
        PaneAddress address = PaneAddress.parse(selector);
        return switch (address.kind()) {
            case TAB, QUALIFIED -> address.tabId();
            case PANE, FOCUSED -> inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                () -> surface.resolve(address)).tabId();
        };
    }

    /** Serialises one of the package's value records. */
    static JsonElement tree(Object value) {
        return ControlJson.toTree(value);
    }

    /** Runs one UI hop, letting the package's checked exception cross the supplier boundary. */
    static <T> T inUi(UiDispatcher ui, long budgetMillis, ThrowingSupplier<T> body)
            throws ControlApiException {
        return UiCalls.await(ui, budgetMillis, () -> {
            try {
                return body.get();
            } catch (ControlApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** A body that may fail with the package's checked exception. */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws ControlApiException;
    }
}
