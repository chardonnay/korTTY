package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.FakePaneAccess;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class LayoutVerbsTest {

    private FakeControlSurface surface;

    private ScheduledExecutorService timer;

    private ControlEventBus events;

    private MethodRegistry registry;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addWindow(new WindowInfo("w1", 0, "korTTY", true, 2));
        surface.addWindow(new WindowInfo("w2", 1, "korTTY", false, 1));
        surface.addTab(new TabInfo("t1", "w1", "shell", "LOCAL_SHELL", "localhost", true, true, 2,
            new AgentRollupInfo(0, 0, 0, 0, 0, null)));
        surface.addTab(new TabInfo("t2", "w1", "ssh", "SSH", "build", false, true, 1,
            new AgentRollupInfo(0, 0, 0, 0, 0, null)));
        surface.addTab(new TabInfo("t3", "w2", "shell", "LOCAL_SHELL", "localhost", true, true, 1,
            new AgentRollupInfo(0, 0, 0, 0, 0, null)));
        surface.addPane(FakeControlSurface.pane("p1", "t1", "w1", 0, true, true, 4711L));
        surface.addPane(FakeControlSurface.pane("p2", "t1", "w1", 1, true, true, 4712L));
        surface.addPane(FakeControlSurface.pane("p3", "t2", "w1", 0, false, true, -1L));
        surface.addPane(FakeControlSurface.pane("p4", "t3", "w2", 0, true, true, 4713L));
        surface.setFocusedPaneId("p2");

        timer = new ScheduledThreadPoolExecutor(1);
        events = new ControlEventBus(timer, () -> 1_000L);
        CodingAgentRegistry agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), () -> 1_000L);
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        registry = ControlVerbs.build(surface, UiDispatcher.DIRECT, agents, actions, events,
            (verb, pane, detail) -> { }, null, () -> 1_000L, "3.4.1", "test-instance");
    }

    @AfterMethod
    void tearDown() {
        events.closeAll();
        timer.shutdownNow();
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        return registry.dispatch(session(), new ControlRequest(new JsonPrimitive(1), method, params))
            .getAsJsonObject();
    }

    private static JsonObject params() {
        return new JsonObject();
    }

    private static JsonArray ids(JsonArray array, String field) {
        JsonArray result = new JsonArray();
        for (JsonElement element : array) {
            result.add(element.getAsJsonObject().get(field));
        }
        return result;
    }

    @Test
    void windowListReportsEveryWindowAndTheRunningInstance() throws Exception {
        JsonObject result = call("window.list", params());

        assertThat(result.get("instance").getAsString()).isEqualTo("test-instance");
        assertThat(ids(result.getAsJsonArray("windows"), "window_id").toString())
            .isEqualTo("[\"w1\",\"w2\"]");
    }

    @Test
    void tabListFiltersByWindow() throws Exception {
        JsonObject params = params();
        params.addProperty("window", "w2");

        JsonObject result = call("tab.list", params);

        assertThat(ids(result.getAsJsonArray("tabs"), "tab_id").toString()).isEqualTo("[\"t3\"]");
    }

    @Test
    void anUnknownWindowIsWindowNotFound() {
        JsonObject params = params();
        params.addProperty("window", "w99");

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call("tab.list", params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.WINDOW_NOT_FOUND);
    }

    @Test
    void paneListFiltersByTabAndByLocalShell() throws Exception {
        JsonObject byTab = params();
        byTab.addProperty("tab", "t1");
        assertThat(ids(call("pane.list", byTab).getAsJsonArray("panes"), "pane_id").toString())
            .isEqualTo("[\"p1\",\"p2\"]");

        JsonObject localOnly = params();
        localOnly.addProperty("local_shell_only", true);
        assertThat(ids(call("pane.list", localOnly).getAsJsonArray("panes"), "pane_id").toString())
            .isEqualTo("[\"p1\",\"p2\",\"p4\"]");
    }

    @Test
    void aTabSelectorAlsoAcceptsAPaneIdAndTheFocusedAlias() throws Exception {
        JsonObject byPane = params();
        byPane.addProperty("tab", "p3");
        assertThat(ids(call("pane.list", byPane).getAsJsonArray("panes"), "pane_id").toString())
            .isEqualTo("[\"p3\"]");

        JsonObject byFocus = params();
        byFocus.addProperty("tab", "@focused");
        assertThat(ids(call("pane.list", byFocus).getAsJsonArray("panes"), "pane_id").toString())
            .isEqualTo("[\"p1\",\"p2\"]");
    }

    @Test
    void aDuplicatePaneIdAcrossLivePanesIsAmbiguousRatherThanAGuess() {
        surface.addPane(FakeControlSurface.pane("p1", "t3", "w2", 1, true, true, 4714L));
        JsonObject params = params();
        params.addProperty("pane", "p1");

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call("pane.get", params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.AMBIGUOUS_PANE);
    }

    @Test
    void aBareWindowIdWhereAPaneIsExpectedIsAmbiguous() {
        JsonObject params = params();
        params.addProperty("pane", "w1");

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call("pane.get", params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.AMBIGUOUS_PANE);
    }

    @Test
    void paneCurrentReportsTheFocusedPane() throws Exception {
        JsonObject result = call("pane.current", params());

        assertThat(result.getAsJsonObject("pane").get("pane_id").getAsString()).isEqualTo("p2");
    }

    @Test
    void paneResolveReturnsTheFirstMatchingPidInOrder() throws Exception {
        JsonObject params = params();
        JsonArray pids = new JsonArray();
        pids.add(9999L);
        pids.add(4712L);
        pids.add(4711L);
        params.add("pids", pids);

        JsonObject result = call("pane.resolve", params);

        assertThat(result.getAsJsonObject("pane").get("pane_id").getAsString()).isEqualTo("p2");
        assertThat(result.get("matched_pid").getAsLong()).isEqualTo(4712L);
    }

    @Test
    void paneResolveReportsNullWhenNothingMatches() throws Exception {
        JsonObject params = params();
        JsonArray pids = new JsonArray();
        pids.add(1L);
        params.add("pids", pids);

        JsonObject result = call("pane.resolve", params);

        assertThat(result.get("pane").isJsonNull()).isTrue();
        assertThat(result.get("matched_pid").isJsonNull()).isTrue();
    }

    @Test
    void moreThanThirtyTwoPidsIsInvalidParams() {
        JsonObject params = params();
        JsonArray pids = new JsonArray();
        for (int i = 0; i <= ControlApiProtocol.MAX_RESOLVE_PIDS; i++) {
            pids.add((long) i);
        }
        params.add("pids", pids);

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call("pane.resolve", params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(failure.data()).containsEntry("max", ControlApiProtocol.MAX_RESOLVE_PIDS);
    }

    @Test
    void paneFocusReportsTheRequestAsDispatchedWithoutRereadingTheFocusedWidget() throws Exception {
        JsonObject params = params();
        params.addProperty("pane", "p1");

        JsonObject result = call("pane.focus", params);

        assertThat(result.get("ok").getAsBoolean()).isTrue();
        assertThat(surface.focusedPanes()).containsExactly("p1");
    }

    @Test
    void tabFocusRaisesTheTabAndEchoesIt() throws Exception {
        JsonObject params = params();
        params.addProperty("tab", "t3");

        JsonObject result = call("tab.focus", params);

        assertThat(result.get("ok").getAsBoolean()).isTrue();
        assertThat(result.getAsJsonObject("tab").get("tab_id").getAsString()).isEqualTo("t3");
        assertThat(surface.focusedTabs()).containsExactly("t3");
    }

    @Test
    void anUnknownTabIsTabNotFound() {
        JsonObject params = params();
        params.addProperty("tab", "t99");

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call("tab.focus", params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TAB_NOT_FOUND);
    }

    private static ControlSession session() {
        return new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test", frame -> { });
    }
}
