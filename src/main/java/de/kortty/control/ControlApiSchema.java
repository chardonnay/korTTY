package de.kortty.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the {@code api.schema} document from the very {@link MethodSpec} records the dispatcher
 * registers, so the discoverable surface cannot drift from the implementation.
 *
 * <p>Pure, any thread.
 */
public final class ControlApiSchema {

    /** One short sentence per error code, so a client never has to guess what one means. */
    private static final Map<ControlErrorCode, String> ERROR_DOCS = errorDocs();

    /**
     * The members of every {@code agent.*} frame: {@link ControlEvent}'s components in declaration
     * order, which is the order Gson writes them.
     */
    private static final List<String> AGENT_EVENT_FIELDS = List.of(
        "kind", "at_millis", "window_id", "tab_id", "pane_id", "agent", "previous_state", "reason");

    /**
     * The members of an {@code events.overflow} frame, which the bus builds itself and which shares
     * not one id with an agent frame — {@code dropped} is the only useful member on it.
     */
    private static final List<String> OVERFLOW_EVENT_FIELDS =
        List.of("kind", "at_millis", "dropped", "subscription_id");

    /**
     * The published event catalogue.
     *
     * <p>The fields live in this table beside their kind, and not in one list shared by every kind,
     * because a client builds its reader for one kind from one entry: a shared list makes six of the
     * seven entries a lie the moment any frame carries something else, which is exactly what
     * {@code events.overflow} does.
     */
    private static final List<EventDoc> EVENT_DOCS = List.of(
        new EventDoc("agent.added", AGENT_EVENT_FIELDS, "An agent was registered for a pane."),
        new EventDoc("agent.state_changed", AGENT_EVENT_FIELDS, "An agent's effective state changed."),
        new EventDoc("agent.seen", AGENT_EVENT_FIELDS,
            "The user looked at a pane whose agent was done."),
        new EventDoc("agent.alias_changed", AGENT_EVENT_FIELDS, "An agent's alias was set or cleared."),
        new EventDoc("agent.removed", AGENT_EVENT_FIELDS,
            "An agent left; agent is null and only the ids are filled in."),
        new EventDoc("agent.evidence", AGENT_EVENT_FIELDS,
            "The detection evidence line changed; opt-in and rate-limited."),
        new EventDoc("events.overflow", OVERFLOW_EVENT_FIELDS,
            "Frames were dropped; dropped says how many and subscription_id which stream lost them."));

    private ControlApiSchema() {
    }

    /** The whole schema document. */
    public static JsonObject document(List<MethodSpec> methods, List<ReservedSpec> reserved,
                                      String appVersion) {
        JsonObject document = new JsonObject();
        document.addProperty("api", ControlApiProtocol.API_NAME);
        document.addProperty("protocol_version", ControlApiProtocol.PROTOCOL_VERSION);
        document.addProperty("app_version", appVersion);
        document.add("id_formats", idFormats());

        JsonArray methodArray = new JsonArray();
        if (methods != null) {
            for (MethodSpec spec : methods) {
                methodArray.add(method(spec));
            }
        }
        document.add("methods", methodArray);

        JsonArray reservedArray = new JsonArray();
        if (reserved != null) {
            for (ReservedSpec spec : reserved) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", spec.name());
                entry.addProperty("reason", spec.reason());
                reservedArray.add(entry);
            }
        }
        document.add("reserved", reservedArray);

        document.add("errors", errors());
        document.add("events", events());
        document.add("keys", ControlJson.toTree(ControlKeyTable.knownKeys()));
        document.add("limits", limits());
        document.add("notes", notes());
        return document;
    }

    /** One method entry, as it appears inside {@link #document}. */
    public static JsonObject method(MethodSpec spec) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", spec.name());
        entry.addProperty("summary", spec.summary());
        entry.addProperty("mutates", spec.mutates());
        entry.addProperty("blocking", spec.blocking());
        entry.addProperty("cli", spec.cli());

        JsonArray params = new JsonArray();
        for (ParamSpec param : spec.params()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", param.name());
            item.addProperty("type", param.type());
            item.addProperty("required", param.required());
            item.addProperty("default", param.defaultValue());
            item.addProperty("doc", param.doc());
            params.add(item);
        }
        entry.add("params", params);
        entry.addProperty("result", spec.result());

        JsonArray errors = new JsonArray();
        for (ControlErrorCode code : spec.errors()) {
            errors.add(code.wire());
        }
        entry.add("errors", errors);
        entry.add("example_request", example(spec.exampleRequest()));
        entry.add("example_response", example(spec.exampleResponse()));
        return entry;
    }

    private static JsonObject idFormats() {
        JsonObject formats = new JsonObject();
        formats.addProperty("window", "w<n>");
        formats.addProperty("tab", "t<uuid>");
        formats.addProperty("pane", "p<hex>");
        formats.addProperty("qualified", "<w>:<t>:<p>");
        JsonArray aliases = new JsonArray();
        aliases.add("@focused");
        formats.add("aliases", aliases);
        formats.addProperty("stable", "session only — no id survives a restart");
        return formats;
    }

    private static JsonArray errors() {
        JsonArray array = new JsonArray();
        for (ControlErrorCode code : ControlErrorCode.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("code", code.wire());
            entry.addProperty("json_rpc", code.jsonRpcCode());
            entry.addProperty("exit", code.cliExit());
            entry.addProperty("retryable", code.retryable());
            entry.addProperty("doc", ERROR_DOCS.get(code));
            array.add(entry);
        }
        return array;
    }

    private static JsonArray events() {
        JsonArray array = new JsonArray();
        for (EventDoc event : EVENT_DOCS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", event.kind());
            entry.add("fields", ControlJson.toTree(event.fields()));
            entry.addProperty("doc", event.doc());
            array.add(entry);
        }
        return array;
    }

    private static JsonObject limits() {
        JsonObject limits = new JsonObject();
        limits.addProperty("max_line_bytes", ControlApiProtocol.MAX_LINE_BYTES);
        limits.addProperty("max_result_bytes", ControlApiProtocol.MAX_RESULT_BYTES);
        limits.addProperty("max_connections", ControlApiProtocol.MAX_CONNECTIONS);
        limits.addProperty("max_wait_ms", ControlApiProtocol.WAIT_HARD_CAP_MILLIS);
        limits.addProperty("event_queue_depth", ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);
        limits.addProperty("max_read_lines", ControlApiProtocol.MAX_READ_LINES);
        limits.addProperty("max_regex_chars", ControlApiProtocol.MAX_REGEX_CHARS);
        limits.addProperty("max_resolve_pids", ControlApiProtocol.MAX_RESOLVE_PIDS);
        return limits;
    }

    private static JsonArray notes() {
        JsonArray notes = new JsonArray();
        notes.add("requests on one connection are executed sequentially");
        notes.add("open a second connection for a long wait plus concurrent calls");
        notes.add("ids are session-scoped; re-enumerate when instance_id changes");
        return notes;
    }

    private static JsonElement example(String text) {
        if (text == null || text.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return ControlJson.parseObjectStrict(text.strip());
        } catch (ControlApiException e) {
            return new JsonPrimitive(text);
        }
    }

    private static Map<ControlErrorCode, String> errorDocs() {
        Map<ControlErrorCode, String> docs = new EnumMap<>(ControlErrorCode.class);
        docs.put(ControlErrorCode.PARSE_ERROR, "The line was not JSON.");
        docs.put(ControlErrorCode.INVALID_REQUEST, "Missing id, a batch array, or a notification.");
        docs.put(ControlErrorCode.UNKNOWN_METHOD, "No such method; reserved verbs answer unsupported.");
        docs.put(ControlErrorCode.INVALID_PARAMS, "A parameter is missing, mistyped or out of range.");
        docs.put(ControlErrorCode.INTERNAL_ERROR, "A bug; the detail is in the korTTY log.");
        docs.put(ControlErrorCode.MESSAGE_TOO_LARGE, "The line exceeded the cap; the connection closed.");
        docs.put(ControlErrorCode.UNAUTHORIZED, "Bad or missing token, or a method before auth.");
        docs.put(ControlErrorCode.CONTROL_API_DISABLED, "The control API setting is off.");
        docs.put(ControlErrorCode.BLOCKED_BY_POLICY, "Enterprise policy denies control-api.");
        docs.put(ControlErrorCode.TOO_MANY_CONNECTIONS, "The connection limit is reached; retry later.");
        docs.put(ControlErrorCode.NOT_READY, "korTTY is still starting up; retry shortly.");
        docs.put(ControlErrorCode.WINDOW_NOT_FOUND, "No window with that id is open.");
        docs.put(ControlErrorCode.TAB_NOT_FOUND, "No tab with that id is open.");
        docs.put(ControlErrorCode.PANE_NOT_FOUND, "No pane with that id is open.");
        docs.put(ControlErrorCode.AGENT_NOT_FOUND, "The pane has no registered coding agent.");
        docs.put(ControlErrorCode.AMBIGUOUS_PANE, "The selector matches more than one pane.");
        docs.put(ControlErrorCode.STALE_INSTANCE, "The instance parameter names a previous korTTY run.");
        docs.put(ControlErrorCode.UNKNOWN_KEY, "A key name is not in the vocabulary; see data.known.");
        docs.put(ControlErrorCode.INVALID_REGEX, "The pattern did not compile; see data.detail.");
        docs.put(ControlErrorCode.EMPTY_INPUT, "There was nothing to write.");
        docs.put(ControlErrorCode.HOST_SHORTCUT_CONFLICT,
            "korTTY's own AI shortcut would swallow the first line; see data.shortcut.");
        docs.put(ControlErrorCode.NOT_CONNECTED, "The pane's connection is down.");
        docs.put(ControlErrorCode.WRITE_FAILED, "The write to the pty failed.");
        docs.put(ControlErrorCode.AGENT_BLOCKED, "Answer the agent's prompt with agent.send_keys first.");
        docs.put(ControlErrorCode.BUSY,
            "A rate limit refused the call; notification.show accepts one per 5 s per connection.");
        docs.put(ControlErrorCode.LAST_PANE, "A tab's last pane cannot be closed; close the tab yourself.");
        docs.put(ControlErrorCode.UNSUPPORTED, "Not possible here, or a reserved verb.");
        docs.put(ControlErrorCode.SPLIT_FAILED, "The split aborted before the new pane was connected.");
        docs.put(ControlErrorCode.UI_UNAVAILABLE, "No window is open, or the toolkit is gone.");
        docs.put(ControlErrorCode.TIMEOUT, "A wait or a UI hop exceeded its budget; see data.stage.");
        return Collections.unmodifiableMap(docs);
    }

    /**
     * One entry of the event catalogue.
     *
     * <p>Kind, fields and prose are declared together so they cannot drift apart.
     *
     * @param kind the wire kind, one of {@link ControlEvent#KINDS}
     * @param fields the members that kind's frame carries, in the order they are written
     * @param doc one sentence about what the kind means
     */
    private record EventDoc(String kind, List<String> fields, String doc) {
    }
}
