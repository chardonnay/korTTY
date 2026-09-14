package de.kortty.control;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pane read and write verbs: {@code pane.read}, {@code pane.send_text}, {@code pane.run},
 * {@code pane.send_keys} and {@code pane.wait_output}.
 *
 * <p>None of these goes through {@code CodingAgentActions}: every verb there begins with
 * {@code requireEntry(pane)} and would answer {@code pane_not_found} for a perfectly ordinary
 * agent-less pane.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class PaneIoVerbs {

    /** The parameter naming how many rows {@code pane.read} returns. */
    private static final String PARAM_LINES = "lines";

    private PaneIoVerbs() {
    }

    /**
     * Registers the pane I/O surface.
     *
     * @param builder the table being assembled
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param writer the shared write path
     * @param waiter the output waiter
     * @param agents the agent gateway, which answers {@code pane.read mode=detection} from the
     *     already-published registry snapshot — the detector is never re-run on a request path
     * @param instanceId the id minted at server start
     */
    static void register(MethodRegistry.Builder builder, ControlSurface surface, UiDispatcher ui,
                         ControlPaneWriter writer, PaneOutputWaiter waiter,
                         ControlAgentGateway agents, String instanceId) {
        registerRead(builder, surface, ui, agents);
        registerSendText(builder, surface, ui, writer, instanceId);
        registerRun(builder, surface, ui, writer, instanceId);
        registerSendKeys(builder, surface, ui, writer, instanceId);
        registerWaitOutput(builder, surface, ui, waiter);
    }

    private static void registerRead(MethodRegistry.Builder builder, ControlSurface surface,
                                     UiDispatcher ui, ControlAgentGateway agents) {
        builder.register(new MethodSpec("pane.read",
                "Reads a pane's visible screen, its recent scrollback, or the coding-agent detection"
                    + " the monitor last published.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("mode", "string", false, "visible",
                        "visible, recent or detection."),
                    new ParamSpec(PARAM_LINES, "int", false,
                        String.valueOf(ControlApiProtocol.DEFAULT_READ_LINES),
                        "Rows for recent mode; clamped to "
                            + ControlApiProtocol.MAX_READ_LINES + ".")),
                "PaneText, or the detection document",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AMBIGUOUS_PANE,
                    ControlErrorCode.INVALID_PARAMS),
                false, false,
                "kortty-cli pane read <selector> --recent",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.read\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pane_id\":\"p1a2b\",\"lines\":[]}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                ReadMode mode = ReadMode.parse(
                    ControlJson.optString(params, "mode", ReadMode.VISIBLE.wire()));
                int lines = readLines(params);
                if (mode == ReadMode.DETECTION) {
                    return detection(agents, pane);
                }
                PaneReader reader = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    () -> surface.readerFor(pane.paneId()).orElse(null));
                if (reader == null) {
                    throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND,
                        "No pane " + pane.paneId() + " is open",
                        Map.of(BaseVerbs.PARAM_PANE, pane.paneId()));
                }
                // Off the UI thread on purpose: a 10 000-line scrollback read must not stall it.
                PaneText text = reader.read(mode, lines);
                return BaseVerbs.tree(truncate(pane.paneId(), mode, text));
            });
    }

    private static void registerSendText(MethodRegistry.Builder builder, ControlSurface surface,
                                         UiDispatcher ui, ControlPaneWriter writer, String instanceId) {
        builder.register(new MethodSpec("pane.send_text",
                "Types text into any pane, agent or not.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("text", "string", true, null, "The text; \\n becomes CR."),
                    new ParamSpec("submit", "bool", false, "false", "Append a carriage return."),
                    new ParamSpec("bracketed", "string", false, "auto",
                        "auto, never or always; auto wraps a multi-line payload when the pane has"
                            + " enabled DECSET 2004."),
                    new ParamSpec("allow_shortcut_conflict", "bool", false, "false",
                        "Write even though korTTY's own AI shortcut would swallow the first line."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "WriteResult",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.NOT_CONNECTED,
                    ControlErrorCode.WRITE_FAILED, ControlErrorCode.EMPTY_INPUT,
                    ControlErrorCode.HOST_SHORTCUT_CONFLICT, ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli pane send-text <selector> --text <s>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.send_text\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"text\":\"ls\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"bytes_written\":2}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String text = ControlJson.requireString(params, "text");
                boolean submit = ControlJson.optBool(params, "submit", false);
                String bracketed = ControlJson.optString(params, "bracketed", "auto");
                boolean allow = ControlJson.optBool(params, "allow_shortcut_conflict", false);
                return BaseVerbs.tree(writer.sendText(pane.paneId(), text, submit, bracketed, allow));
            });
    }

    private static void registerRun(MethodRegistry.Builder builder, ControlSurface surface,
                                    UiDispatcher ui, ControlPaneWriter writer, String instanceId) {
        builder.register(new MethodSpec("pane.run",
                "Types one command line plus a carriage return. An embedded line break is refused so"
                    + " one audited command cannot smuggle a second one.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("command", "string", true, null, "One single-line command."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "WriteResult",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.NOT_CONNECTED,
                    ControlErrorCode.WRITE_FAILED, ControlErrorCode.EMPTY_INPUT,
                    ControlErrorCode.INVALID_PARAMS, ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli pane run <selector> -- <command>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.run\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"command\":\"ls -l\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"bytes_written\":6,\"submitted\":true}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String command = ControlJson.requireString(params, "command");
                return BaseVerbs.tree(writer.run(pane.paneId(), command));
            });
    }

    private static void registerSendKeys(MethodRegistry.Builder builder, ControlSurface surface,
                                         UiDispatcher ui, ControlPaneWriter writer, String instanceId) {
        builder.register(new MethodSpec("pane.send_keys",
                "Presses keys in any pane. api.schema.keys publishes the whole vocabulary, so an agent"
                    + " never has to guess a name.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("keys", "string[]|string", true, null,
                        "Key names, as an array or space-separated."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "WriteResult with the normalised key names",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.NOT_CONNECTED,
                    ControlErrorCode.WRITE_FAILED, ControlErrorCode.EMPTY_INPUT,
                    ControlErrorCode.UNKNOWN_KEY, ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli pane send-keys <selector> ctrl+c enter",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.send_keys\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"keys\":\"ctrl+c enter\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"keys\":[\"ctrl+c\",\"enter\"]}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                return BaseVerbs.tree(writer.sendKeys(pane.paneId(), keys(params)));
            });
    }

    private static void registerWaitOutput(MethodRegistry.Builder builder, ControlSurface surface,
                                           UiDispatcher ui, PaneOutputWaiter waiter) {
        builder.register(new MethodSpec("pane.wait_output",
                "Waits until a pane's output matches. Polls the read handle off the UI thread, so a"
                    + " connection that dies mid-wait leaves nothing registered to leak.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("regex", "string", false, null,
                        "A pattern, at most " + ControlApiProtocol.MAX_REGEX_CHARS + " characters."),
                    new ParamSpec("contains", "string", false, null, "A literal substring."),
                    new ParamSpec("mode", "string", false, "recent", "visible or recent."),
                    new ParamSpec(PARAM_LINES, "int", false,
                        String.valueOf(ControlApiProtocol.DEFAULT_READ_LINES), "Rows to search."),
                    new ParamSpec("timeout_ms", "int", false,
                        String.valueOf(ControlApiProtocol.WAIT_DEFAULT_MILLIS),
                        "Clamped to " + ControlApiProtocol.WAIT_HARD_CAP_MILLIS + " ms."),
                    new ParamSpec("poll_ms", "int", false,
                        String.valueOf(ControlApiProtocol.WAIT_POLL_MILLIS),
                        "At least " + ControlApiProtocol.WAIT_POLL_MIN_MILLIS + " ms.")),
                "MatchResult",
                // No BUSY: PaneOutputWaiter holds no per-connection wait registry and never raises
                // it, and one connection executes one request at a time, so a wait cannot collide
                // with another wait either. A declared refusal no request can provoke is a branch
                // every generated client carries and can never reach.
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.INVALID_REGEX,
                    ControlErrorCode.INVALID_PARAMS, ControlErrorCode.TIMEOUT),
                false, true,
                "kortty-cli pane wait-output <selector> --contains <s>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.wait_output\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"contains\":\"$ \"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"matched\":true}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String regex = ControlJson.optString(params, "regex", null);
                String contains = ControlJson.optString(params, "contains", null);
                ReadMode mode = ReadMode.parse(
                    ControlJson.optString(params, "mode", ReadMode.RECENT.wire()));
                int lines = readLines(params);
                long timeout = ControlJson.optLong(params, "timeout_ms",
                    ControlApiProtocol.WAIT_DEFAULT_MILLIS, 1L, Long.MAX_VALUE);
                long poll = ControlJson.optLong(params, "poll_ms", ControlApiProtocol.WAIT_POLL_MILLIS,
                    1L, ControlApiProtocol.WAIT_HARD_CAP_MILLIS);
                return BaseVerbs.tree(
                    waiter.await(pane.paneId(), regex, contains, mode, lines, timeout, poll));
            });
    }

    /** The detection document, built from the registry entry the monitor already published. */
    private static JsonObject detection(ControlAgentGateway agents, PaneInfo pane)
            throws ControlApiException {
        JsonObject result = new JsonObject();
        result.addProperty("pane_id", pane.paneId());
        result.addProperty("mode", ReadMode.DETECTION.wire());
        AgentInfo agent;
        try {
            agent = agents.get(pane.paneId());
        } catch (ControlApiException e) {
            if (e.code() != ControlErrorCode.AGENT_NOT_FOUND) {
                throw e;
            }
            result.addProperty("detected", false);
            return result;
        }
        result.addProperty("detected", true);
        result.addProperty("kind", agent.kind());
        result.addProperty("state", agent.state());
        result.addProperty("matched_rule_id", agent.matchedRuleId());
        result.addProperty("evidence", agent.evidence());
        result.addProperty("explain", agents.explain(pane.paneId()));
        return result;
    }

    /**
     * Drops leading lines until the text fits {@link ControlApiProtocol#MAX_RESULT_BYTES}, so the
     * server can never emit a frame its own codec would reject.
     */
    private static PaneText truncate(String paneId, ReadMode mode, PaneText text) {
        PaneText source = text == null
            ? new PaneText(paneId, mode.wire(), List.of(), 0, 0, false, null, false)
            : text;
        List<String> lines = source.lines();
        long bytes = 0L;
        for (String line : lines) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        }
        if (bytes <= ControlApiProtocol.MAX_RESULT_BYTES) {
            return source;
        }
        List<String> kept = new ArrayList<>();
        long budget = 0L;
        for (int i = lines.size() - 1; i >= 0; i--) {
            long size = lines.get(i).getBytes(StandardCharsets.UTF_8).length + 1L;
            if (budget + size > ControlApiProtocol.MAX_RESULT_BYTES) {
                break;
            }
            budget += size;
            kept.add(0, lines.get(i));
        }
        return new PaneText(source.paneId(), source.mode(), kept, source.columns(), source.rows(),
            source.alternateScreen(), source.oscTitle(), true);
    }

    /** The {@code lines} parameter, clamped rather than refused, as the schema promises. */
    private static int readLines(JsonObject params) throws ControlApiException {
        long requested = ControlJson.optLong(params, PARAM_LINES,
            ControlApiProtocol.DEFAULT_READ_LINES, 1L, Integer.MAX_VALUE);
        return (int) Math.min(requested, ControlApiProtocol.MAX_READ_LINES);
    }

    /** The {@code keys} parameter, accepting both the array and the space-separated form. */
    private static List<String> keys(JsonObject params) throws ControlApiException {
        List<String> raw = ControlJson.optStringList(params, "keys");
        if (raw.size() == 1) {
            List<String> split = ControlKeyTable.split(raw.get(0));
            if (!split.isEmpty()) {
                return split;
            }
        }
        return raw;
    }
}
