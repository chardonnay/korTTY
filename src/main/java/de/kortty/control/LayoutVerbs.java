package de.kortty.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The enumeration, addressing and layout verbs: {@code window.list}, {@code tab.list},
 * {@code tab.focus}, {@code pane.list}, {@code pane.current}, {@code pane.get}, {@code pane.resolve},
 * {@code pane.focus}, {@code pane.split} and {@code pane.close}, plus the three reserved tab verbs.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class LayoutVerbs {

    /** The reason published for every verb this version does not implement. */
    private static final String RESERVED_REASON = "not_implemented_in_this_version";

    private LayoutVerbs() {
    }

    /**
     * Registers the layout surface.
     *
     * @param builder the table being assembled
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param splits the split service, which owns the only layout mutation
     * @param instanceId the id minted at server start
     */
    static void register(MethodRegistry.Builder builder, ControlSurface surface, UiDispatcher ui,
                         ControlSplitService splits, String instanceId) {
        registerWindowList(builder, surface, ui, instanceId);
        registerTabList(builder, surface, ui, instanceId);
        registerTabFocus(builder, surface, ui, instanceId);
        registerPaneList(builder, surface, ui, instanceId);
        registerPaneCurrent(builder, surface, ui);
        registerPaneGet(builder, surface, ui);
        registerPaneResolve(builder, surface, ui);
        registerPaneFocus(builder, surface, ui, instanceId);
        registerPaneSplit(builder, surface, ui, splits, instanceId);
        registerPaneClose(builder, surface, ui, splits, instanceId);

        builder.reserve(new ReservedSpec("tab.create", RESERVED_REASON));
        builder.reserve(new ReservedSpec("tab.close", RESERVED_REASON));
        builder.reserve(new ReservedSpec("tab.rename", RESERVED_REASON));
    }

    private static void registerWindowList(MethodRegistry.Builder builder, ControlSurface surface,
                                           UiDispatcher ui, String instanceId) {
        builder.register(new MethodSpec("window.list",
                "Lists every open korTTY window.",
                List.of(), "{instance, windows:[WindowInfo]}", List.of(), false, false,
                "kortty-cli window list",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"window.list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"windows\":[{\"window_id\":\"w1\"}]}}"),
            (session, params) -> {
                List<WindowInfo> windows = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    surface::listWindows);
                JsonObject result = new JsonObject();
                result.addProperty(BaseVerbs.PARAM_INSTANCE, instanceId);
                result.add("windows", BaseVerbs.tree(windows));
                return result;
            });
    }

    private static void registerTabList(MethodRegistry.Builder builder, ControlSurface surface,
                                        UiDispatcher ui, String instanceId) {
        builder.register(new MethodSpec("tab.list",
                "Lists the tabs of one window, or of every window.",
                List.of(new ParamSpec(BaseVerbs.PARAM_WINDOW, "string", false, null,
                    "A window id; omit for every window.")),
                "{instance, tabs:[TabInfo]}",
                List.of(ControlErrorCode.WINDOW_NOT_FOUND), false, false,
                "kortty-cli tab list",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tab.list\",\"params\":{\"window\":\"w1\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tabs\":[{\"tab_id\":\"t9f3a\"}]}}"),
            (session, params) -> {
                String windowId = ControlJson.optString(params, BaseVerbs.PARAM_WINDOW, null);
                List<TabInfo> tabs = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    () -> surface.listTabs(windowId));
                JsonObject result = new JsonObject();
                result.addProperty(BaseVerbs.PARAM_INSTANCE, instanceId);
                result.add("tabs", BaseVerbs.tree(tabs));
                return result;
            });
    }

    private static void registerTabFocus(MethodRegistry.Builder builder, ControlSurface surface,
                                         UiDispatcher ui, String instanceId) {
        builder.register(new MethodSpec("tab.focus",
                "Raises the window, selects the tab and lands on its current pane.",
                List.of(new ParamSpec(BaseVerbs.PARAM_TAB, "tab_ref", true, null,
                        "A tab id, the tab part of a qualified address, a pane id or @focused."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{ok, tab:TabInfo}",
                List.of(ControlErrorCode.TAB_NOT_FOUND, ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli tab focus --tab <id>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tab.focus\",\"params\":{\"tab\":\"t9f3a\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                String tabId = requiredTabId(surface, ui, params);
                TabInfo focused = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> {
                    surface.focusTab(tabId);
                    return findTab(surface.listTabs(null), tabId);
                });
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.add(BaseVerbs.PARAM_TAB, BaseVerbs.tree(focused));
                return result;
            });
    }

    private static void registerPaneList(MethodRegistry.Builder builder, ControlSurface surface,
                                         UiDispatcher ui, String instanceId) {
        builder.register(new MethodSpec("pane.list",
                "Lists the panes of one tab, of one window, or of everything.",
                List.of(new ParamSpec(BaseVerbs.PARAM_WINDOW, "string", false, null, "A window id."),
                    new ParamSpec(BaseVerbs.PARAM_TAB, "tab_ref", false, null, "A tab selector."),
                    new ParamSpec("local_shell_only", "bool", false, "false",
                        "Keep only panes whose connection is a local shell.")),
                "{instance, panes:[PaneInfo]}",
                List.of(ControlErrorCode.WINDOW_NOT_FOUND, ControlErrorCode.TAB_NOT_FOUND),
                false, false,
                "kortty-cli pane list",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"panes\":[{\"pane_id\":\"p1a2b\"}]}}"),
            (session, params) -> {
                String windowId = ControlJson.optString(params, BaseVerbs.PARAM_WINDOW, null);
                String tabId = BaseVerbs.optionalTabId(surface, ui, params, BaseVerbs.PARAM_TAB);
                boolean localOnly = ControlJson.optBool(params, "local_shell_only", false);
                List<PaneInfo> panes = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    () -> surface.listPanes(windowId, tabId));
                List<PaneInfo> filtered = new ArrayList<>();
                for (PaneInfo pane : panes) {
                    if (!localOnly || pane.localShell()) {
                        filtered.add(pane);
                    }
                }
                JsonObject result = new JsonObject();
                result.addProperty(BaseVerbs.PARAM_INSTANCE, instanceId);
                result.add("panes", BaseVerbs.tree(filtered));
                return result;
            });
    }

    private static void registerPaneCurrent(MethodRegistry.Builder builder, ControlSurface surface,
                                            UiDispatcher ui) {
        builder.register(new MethodSpec("pane.current",
                "The pane the user is looking at; deliberately not what --current means in the CLI.",
                List.of(), "{pane:PaneInfo|null}", List.of(), false, false,
                "kortty-cli pane current",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.current\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pane\":null}}"),
            (session, params) -> {
                Optional<PaneInfo> pane = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    surface::focusedPane);
                JsonObject result = new JsonObject();
                result.add(BaseVerbs.PARAM_PANE,
                    pane.<JsonElement>map(BaseVerbs::tree).orElse(JsonNull.INSTANCE));
                return result;
            });
    }

    private static void registerPaneGet(MethodRegistry.Builder builder, ControlSurface surface,
                                        UiDispatcher ui) {
        builder.register(new MethodSpec("pane.get",
                "Describes one pane.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null,
                    "p<id>, w<id>:t<id>:p<id>, t<id> or @focused.")),
                "{pane:PaneInfo}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AMBIGUOUS_PANE),
                false, false,
                "kortty-cli pane get --pane <id>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.get\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pane\":{\"pane_id\":\"p1a2b\"}}}"),
            (session, params) -> {
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                JsonObject result = new JsonObject();
                result.add(BaseVerbs.PARAM_PANE, BaseVerbs.tree(pane));
                return result;
            });
    }

    private static void registerPaneResolve(MethodRegistry.Builder builder, ControlSurface surface,
                                            UiDispatcher ui) {
        builder.register(new MethodSpec("pane.resolve",
                "Finds the pane whose local shell is the first match in an ancestor pid list; this is"
                    + " how a script running inside a pane identifies its own pane.",
                List.of(new ParamSpec("pids", "int[]", true, null,
                    "Process ids, nearest ancestor first, at most "
                        + ControlApiProtocol.MAX_RESOLVE_PIDS + ".")),
                "{pane:PaneInfo|null, matched_pid:int|null}",
                List.of(ControlErrorCode.INVALID_PARAMS), false, false,
                "kortty-cli raw pane.resolve {\"pane\":\"@focused\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.resolve\",\"params\":{\"pids\":[4711]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pane\":null,\"matched_pid\":null}}"),
            (session, params) -> {
                List<Long> pids = pids(params);
                Optional<PaneInfo> pane = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    () -> surface.paneForShellPids(pids));
                JsonObject result = new JsonObject();
                result.add(BaseVerbs.PARAM_PANE,
                    pane.<JsonElement>map(BaseVerbs::tree).orElse(JsonNull.INSTANCE));
                if (pane.isPresent()) {
                    result.addProperty("matched_pid", pane.get().shellPid());
                } else {
                    result.add("matched_pid", JsonNull.INSTANCE);
                }
                return result;
            });
    }

    private static void registerPaneFocus(MethodRegistry.Builder builder, ControlSurface surface,
                                          UiDispatcher ui, String instanceId) {
        builder.register(new MethodSpec("pane.focus",
                "Requests focus for a pane. Success means the request was dispatched: it is never"
                    + " re-read from the focused widget, which a headless window may never update.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec("raise", "bool", false, "true", "Raise the owning window too."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{ok, pane:PaneInfo}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.AMBIGUOUS_PANE,
                    ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli pane focus --pane <id>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.focus\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                boolean ok = BaseVerbs.inUi(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
                    () -> surface.focusPane(pane.paneId()));
                JsonObject result = new JsonObject();
                result.addProperty("ok", ok);
                result.add(BaseVerbs.PARAM_PANE, BaseVerbs.tree(pane));
                return result;
            });
    }

    private static void registerPaneSplit(MethodRegistry.Builder builder, ControlSurface surface,
                                          UiDispatcher ui, ControlSplitService splits,
                                          String instanceId) {
        builder.register(new MethodSpec("pane.split",
                "Splits a local-shell pane and returns the new pane. The connector is connected off"
                    + " the UI thread, so no modal connect dialog can ever open.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "The source pane."),
                    new ParamSpec("orientation", "string", false, "horizontal",
                        "horizontal or vertical."),
                    new ParamSpec("focus", "bool", false, "true", "Give the new pane focus."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{pane:PaneInfo}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.NOT_CONNECTED,
                    ControlErrorCode.UNSUPPORTED, ControlErrorCode.SPLIT_FAILED,
                    ControlErrorCode.STALE_INSTANCE),
                true, true,
                "kortty-cli pane split --pane <id> --vertical",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.split\",\"params\":{\"pane\":\"p1a2b\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pane\":{\"pane_id\":\"p7f31\"}}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo source = BaseVerbs.requirePane(surface, ui, params);
                String orientation = ControlJson.optString(params, "orientation", "horizontal");
                boolean focus = ControlJson.optBool(params, "focus", true);
                PaneInfo created = splits.split(source.paneId(), orientation, focus);
                JsonObject result = new JsonObject();
                result.add(BaseVerbs.PARAM_PANE, BaseVerbs.tree(created));
                return result;
            });
    }

    private static void registerPaneClose(MethodRegistry.Builder builder, ControlSurface surface,
                                          UiDispatcher ui, ControlSplitService splits,
                                          String instanceId) {
        builder.register(new MethodSpec("pane.close",
                "Closes one split pane. A tab's last pane is refused: closing it would leave an empty"
                    + " terminal area inside a still-open tab.",
                List.of(new ParamSpec(BaseVerbs.PARAM_PANE, "pane_ref", true, null, "A pane selector."),
                    new ParamSpec(BaseVerbs.PARAM_INSTANCE, "string", false, null,
                        "The instance the caller enumerated against.")),
                "{ok}",
                List.of(ControlErrorCode.PANE_NOT_FOUND, ControlErrorCode.LAST_PANE,
                    ControlErrorCode.STALE_INSTANCE),
                true, false,
                "kortty-cli pane close --pane <id>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"pane.close\",\"params\":{\"pane\":\"p7f31\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"),
            (session, params) -> {
                BaseVerbs.requireInstance(params, instanceId);
                PaneInfo pane = BaseVerbs.requirePane(surface, ui, params);
                splits.close(pane.paneId());
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                return result;
            });
    }

    private static String requiredTabId(ControlSurface surface, UiDispatcher ui, JsonObject params)
            throws ControlApiException {
        String tabId = BaseVerbs.optionalTabId(surface, ui, params, BaseVerbs.PARAM_TAB);
        if (tabId == null) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Parameter 'tab' is required", Map.of("param", BaseVerbs.PARAM_TAB));
        }
        return tabId;
    }

    private static TabInfo findTab(List<TabInfo> tabs, String tabId) throws ControlApiException {
        for (TabInfo tab : tabs) {
            if (tab.tabId().equals(tabId)) {
                return tab;
            }
        }
        throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND, "No tab " + tabId + " is open",
            Map.of("tab", tabId));
    }

    private static List<Long> pids(JsonObject params) throws ControlApiException {
        JsonElement value = params.get("pids");
        if (value == null || value.isJsonNull() || !value.isJsonArray()) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Parameter 'pids' must be an array of process ids", Map.of("param", "pids"));
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > ControlApiProtocol.MAX_RESOLVE_PIDS) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "At most " + ControlApiProtocol.MAX_RESOLVE_PIDS + " pids may be given",
                Map.of("param", "pids", "max", ControlApiProtocol.MAX_RESOLVE_PIDS,
                    "given", array.size()));
        }
        List<Long> pids = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                    "Parameter 'pids' must contain numbers only", Map.of("param", "pids"));
            }
            pids.add(item.getAsLong());
        }
        return List.copyOf(pids);
    }
}
