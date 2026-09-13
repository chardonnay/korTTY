package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * The {@code agent.*} verbs. Every one of them requires a <strong>registered</strong> coding agent
 * for the pane and answers {@link ControlErrorCode#AGENT_NOT_FOUND} otherwise.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class AgentVerbs {

    /** The parameter naming how long a blocking agent verb may wait. */
    private static final String PARAM_TIMEOUT = "timeout_ms";

    private AgentVerbs() {
    }

    /**
     * Registers the agent surface.
     *
     * @param builder the table being assembled
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param agents the agent gateway
     * @param waiter the state waiter
     * @param starter the {@code agent.start} composition
     * @param instanceId the id minted at server start
     */
    static void register(MethodRegistry.Builder builder, ControlSurface surface, UiDispatcher ui,
                         ControlAgentGateway agents, AgentStateWaiter waiter,
                         AgentStartService starter, String instanceId) {
        registerList(builder, surface, ui, agents);
        registerGet(builder, surface, ui, agents);
        registerExplain(builder, surface, ui, agents);
        registerPrompt(builder, surface, ui, agents, waiter, instanceId);
        registerSendKeys(builder, surface, ui, agents, instanceId);
        registerWait(builder, surface, ui, waiter);
        registerRename(builder, surface, ui, agents, instanceId);
        registerStart(builder, surface, ui, starter, instanceId);
    }

    private static void registerList(MethodRegistry.Builder builder, ControlSurface surface,
                                     UiDispatcher ui, ControlAgentGateway agents) {
        builder.register(new MethodSpec("agent.list",
                "Lists every registered coding agent in the panel's urgency order: blocked first,"
                    + " then longest in state.",
                List.of(new ParamSpec("state", "string", false, null,
                        "blocked, working, done, idle or unknown."),
                    new ParamSpec("kind", "string", false, null,
                        "claude-code, codex or gemini-cli."),
                    new ParamSpec(BaseVerbs.PARAM_TAB, "tab_ref", false, null, "A tab selector."),
                    new ParamSpec(BaseVerbs.PARAM_WINDOW, "string", false, null, "A window id.")),
                "{agents:[AgentInfo], totals:AgentTotals}",
                List.of(ControlErrorCode.TAB_NOT_FOUND, ControlErrorCode.WINDOW_NOT_FOUND),
                false, false,
                "kortty-cli agent list --state blocked",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"agents\":[],\"totals\":{\"total\":0}}}"),
            (session, params) -> {
                String state = ControlJson.optString(params, "state", null);
                String kind = ControlJson.optString(params, "kind", null);
                String tabId = BaseVerbs.optionalTabId(surface, ui, params, BaseVerbs.PARAM_TAB);
                String windowId = ControlJson.optString(params, BaseVerbs.PARAM_WINDOW, null);
                JsonObject result = new JsonObject();
                result.add("agents", BaseVerbs.tree(agents.list(state, kind, tabId, windowId)));
                result.add("totals", BaseVerbs.tree(agents.totals()));
                return result;
            });
    }

    private static void registerGet(MethodRegistry.Builder builder, ControlSurface surface,
                                    UiDispatcher ui, ControlAgentGateway agents) {
        builder.register(new MethodSpec("agent.get",
                "Describes the coding agent registered for one pane. 'detected' follows the registry"
                    + " entry, never the monitor's process sighting alone.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector.")),
                "{agent:AgentInfo}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND),
                false, false,
                "kortty-cli agent get <selector>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.get\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"agent\":{\"kind\":\"claude-code\"}}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                JsonObject result = new JsonObject();
                result.add("agent", BaseVerbs.tree(agents.get(pane.paneId())));
                return result;
            });
    }

    private static void registerExplain(MethodRegistry.Builder builder, ControlSurface surface,
                                        UiDispatcher ui, ControlAgentGateway agents) {
        builder.register(new MethodSpec("agent.explain",
                "The human explanation of the current detection: rule, evidence, process, time in state.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector.")),
                "{explain:string}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND),
                false, false,
                "kortty-cli agent explain <selector>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.explain\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"explain\":\"Claude Code is IDLE\"}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                JsonObject result = new JsonObject();
                result.addProperty("explain", agents.explain(pane.paneId()));
                return result;
            });
    }

    private static void registerPrompt(MethodRegistry.Builder builder, ControlSurface surface,
                                       UiDispatcher ui, ControlAgentGateway agents,
                                       AgentStateWaiter waiter, String instanceId) {
        builder.register(new MethodSpec("agent.prompt",
                "Submits a prompt to the agent and optionally waits for the state it reaches.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("text", "string", true, null, "The prompt."),
                    new ParamSpec("wait_until", "string", false, null,
                        "done, idle, blocked or any; omit not to wait."),
                    new ParamSpec(PARAM_TIMEOUT, "int", false,
                        String.valueOf(ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS),
                        "The wait budget."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "WriteResult, plus the agent.wait fields when wait_until is given",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND,
                    ControlErrorCode.AGENT_BLOCKED, ControlErrorCode.EMPTY_INPUT,
                    ControlErrorCode.NOT_CONNECTED, ControlErrorCode.WRITE_FAILED,
                    ControlErrorCode.HOST_SHORTCUT_CONFLICT, ControlErrorCode.TIMEOUT,
                    ControlErrorCode.STALE_INSTANCE),
                true, true,
                "kortty-cli agent prompt <selector> --text <s> --wait-until done",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.prompt\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"text\":\"hi\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"bytes_written\":3,\"submitted\":true}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String text = ControlJson.requireString(params, "text");
                String until = ControlJson.optString(params, "wait_until", null);
                long timeout = ControlJson.optLong(params, PARAM_TIMEOUT,
                    ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS, 1L, Long.MAX_VALUE);
                WriteResult write = agents.prompt(pane.paneId(), text);
                JsonObject result = BaseVerbs.tree(write).getAsJsonObject();
                if (until != null) {
                    AgentWaitResult waited = waiter.await(pane.paneId(), until, timeout);
                    for (String field : List.of("state", "previous_state", "waited_millis", "agent")) {
                        result.add(field, BaseVerbs.tree(waited).getAsJsonObject().get(field));
                    }
                }
                return result;
            });
    }

    private static void registerSendKeys(MethodRegistry.Builder builder, ControlSurface surface,
                                         UiDispatcher ui, ControlAgentGateway agents,
                                         String instanceId) {
        builder.register(new MethodSpec("agent.send_keys",
                "Presses keys in the agent's pane — the way a permission prompt is answered.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("keys", "string[]|string", true, null,
                        "Key names, as an array or space-separated."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "WriteResult with the normalised key names",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND,
                    ControlErrorCode.UNKNOWN_KEY, ControlErrorCode.EMPTY_INPUT,
                    ControlErrorCode.NOT_CONNECTED, ControlErrorCode.WRITE_FAILED,
                    ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli agent send-keys <selector> y enter",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.send_keys\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"keys\":\"y enter\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"keys\":[\"y\",\"enter\"]}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                List<String> raw = ControlJson.optStringList(params, "keys");
                List<String> names = raw.size() == 1 && !ControlKeyTable.split(raw.get(0)).isEmpty()
                    ? ControlKeyTable.split(raw.get(0))
                    : raw;
                return BaseVerbs.tree(agents.sendKeys(pane.paneId(), names));
            });
    }

    private static void registerWait(MethodRegistry.Builder builder, ControlSurface surface,
                                     UiDispatcher ui, AgentStateWaiter waiter) {
        builder.register(new MethodSpec("agent.wait",
                "Waits until the agent reaches a state. Event-driven, never polled: the current entry"
                    + " is read and the listener registered inside one UI block.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("until", "string", true, null,
                        "blocked, done, idle, working or any."),
                    new ParamSpec(PARAM_TIMEOUT, "int", false,
                        String.valueOf(ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS),
                        "Clamped to " + ControlApiProtocol.WAIT_HARD_CAP_MILLIS + " ms.")),
                "AgentWaitResult",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND,
                    ControlErrorCode.INVALID_PARAMS, ControlErrorCode.TIMEOUT),
                false, true,
                "kortty-cli agent wait <selector> --until done",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.wait\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"until\":\"done\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"state\":\"done\"}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String until = ControlJson.requireString(params, "until");
                long timeout = ControlJson.optLong(params, PARAM_TIMEOUT,
                    ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS, 1L, Long.MAX_VALUE);
                return BaseVerbs.tree(waiter.await(pane.paneId(), until, timeout));
            });
    }

    private static void registerRename(MethodRegistry.Builder builder, ControlSurface surface,
                                       UiDispatcher ui, ControlAgentGateway agents, String instanceId) {
        builder.register(new MethodSpec("agent.rename",
                "Sets or clears the agent's alias. Marshalled to the UI thread because the registry"
                    + " silently drops an off-thread alias write.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("alias", "string", false, null, "A blank alias clears it."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{agent:AgentInfo}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AGENT_NOT_FOUND,
                    ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli agent rename <selector> --alias backend",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.rename\","
                    + "\"params\":{\"pane\":\"p1a2b\",\"alias\":\"backend\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"agent\":{\"alias\":\"backend\"}}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                String alias = ControlJson.optString(params, "alias", null);
                JsonObject result = new JsonObject();
                result.add("agent", BaseVerbs.tree(agents.rename(pane.paneId(), alias)));
                return result;
            });
    }

    private static void registerStart(MethodRegistry.Builder builder, ControlSurface surface,
                                      UiDispatcher ui, AgentStartService starter, String instanceId) {
        builder.register(new MethodSpec("agent.start",
                "Uses or creates a pane, launches a coding agent in it and waits until it is"
                    + " registered; optionally prompts it afterwards.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", false, null,
                        "The pane to launch in."),
                    new ParamSpec("split_from", "pane_ref", false, null,
                        "The pane to split; exactly one of pane and split_from is required."),
                    new ParamSpec("orientation", "string", false, "horizontal",
                        "horizontal or vertical, for the split form."),
                    new ParamSpec("kind", "string", true, null,
                        "claude-code, codex or gemini-cli."),
                    new ParamSpec("command", "string[]", false, null,
                        "The launch command; defaults to the kind's own executable."),
                    new ParamSpec("prompt", "string", false, null,
                        "A prompt to send once the agent is registered."),
                    new ParamSpec("ready_timeout_ms", "int", false,
                        String.valueOf(ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS),
                        "The budget for the connect and detect stages."),
                    new ParamSpec("wait", "bool", false, "false", "Wait after prompting."),
                    new ParamSpec("until", "string", false, "done", "done or idle."),
                    new ParamSpec(PARAM_TIMEOUT, "int", false, "300000", "The budget for that wait."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{pane:PaneInfo, agent:AgentInfo, command:[string]}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.NOT_CONNECTED,
                    ControlErrorCode.UNSUPPORTED, ControlErrorCode.SPLIT_FAILED,
                    ControlErrorCode.INVALID_PARAMS, ControlErrorCode.AGENT_BLOCKED,
                    ControlErrorCode.TIMEOUT, ControlErrorCode.STALE_INSTANCE),
                true, true,
                "kortty-cli agent start --split-from <selector> --kind claude-code",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.start\","
                    + "\"params\":{\"pane\":\"p7f31\",\"kind\":\"claude-code\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"command\":[\"claude\"]}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                String paneId = resolveOptional(surface, ui, params, BaseVerbs.PARAM_PANE);
                String splitFrom = resolveOptional(surface, ui, params, "split_from");
                AgentStartService.Request request = new AgentStartService.Request(paneId, splitFrom,
                    ControlJson.optString(params, "orientation", "horizontal"),
                    ControlJson.requireString(params, "kind"),
                    ControlJson.optStringList(params, "command"),
                    ControlJson.optString(params, "prompt", null),
                    ControlJson.optLong(params, "ready_timeout_ms",
                        ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS, 1L, Long.MAX_VALUE),
                    ControlJson.optBool(params, "wait", false),
                    ControlJson.optString(params, "until", "done"),
                    ControlJson.optLong(params, PARAM_TIMEOUT, 300_000L, 1L, Long.MAX_VALUE));
                AgentStartService.Result started = starter.start(request);
                JsonObject result = new JsonObject();
                result.add(BaseVerbs.PARAM_PANE, pane(surface, ui, started));
                result.add("agent", BaseVerbs.tree(started.agent()));
                result.add("command", BaseVerbs.tree(started.command()));
                return result;
            });
    }

    /**
     * The pane the start landed in. The split form already knows it; the {@code pane} form is
     * re-resolved here, because {@link AgentStartService} deliberately holds no dispatcher and may
     * only touch the surface's ANY-THREAD members.
     */
    private static JsonElement pane(ControlSurface surface, UiDispatcher ui,
                                                    AgentStartService.Result started)
            throws ControlApiException {
        if (started.pane() != null) {
            return BaseVerbs.tree(started.pane());
        }
        AgentInfo agent = started.agent();
        if (agent == null || agent.paneId() == null) {
            return JsonNull.INSTANCE;
        }
        PaneAddress address = PaneAddress.ofPaneId(agent.paneId());
        return BaseVerbs.tree(BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
            () -> surface.resolve(address)));
    }

    /** Resolves an optional pane selector to a concrete pane id, or null when it is absent. */
    private static String resolveOptional(ControlSurface surface, UiDispatcher ui, JsonObject params,
                                          String name) throws ControlApiException {
        String selector = ControlJson.optString(params, name, null);
        if (selector == null) {
            return null;
        }
        PaneAddress address = PaneAddress.parse(selector);
        return BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
            () -> surface.resolve(address)).paneId();
    }
}
