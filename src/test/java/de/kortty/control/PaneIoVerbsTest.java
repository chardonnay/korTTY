package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.CodingAgentTestHarness;
import de.kortty.codingagent.FakePaneAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class PaneIoVerbsTest {

    private static final String PANE = "p1a2b";

    private static final String TAB = "t1";

    private FakeControlSurface surface;

    private FakePaneReader reader;

    private CodingAgentTestHarness harness;

    private ScheduledExecutorService timer;

    private ControlEventBus events;

    private MethodRegistry registry;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, TAB, "w1", 0, true, true, 4711L));
        reader = new FakePaneReader(PANE);
        surface.setReader(PANE, reader);
        harness = new CodingAgentTestHarness();
        timer = new ScheduledThreadPoolExecutor(1);
        events = new ControlEventBus(timer, () -> 1_000L);
        CodingAgentRegistry agents = harness.registry();
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        registry = ControlVerbs.build(surface, UiDispatcher.DIRECT, agents, actions, events,
            (verb, pane, detail) -> { }, null, () -> 1_000L, "3.4.1", "test-instance");
    }

    @AfterMethod
    void tearDown() {
        events.closeAll();
        timer.shutdownNow();
        harness.close();
    }

    private JsonObject read(JsonObject params) throws Exception {
        return registry.dispatch(session(), new ControlRequest(new JsonPrimitive(1), "pane.read", params))
            .getAsJsonObject();
    }

    private static JsonObject params(String pane) {
        JsonObject params = new JsonObject();
        params.addProperty("pane", pane);
        return params;
    }

    @Test
    void visibleAndRecentBothReachTheReaderWithTheirOwnMode() throws Exception {
        reader.setLines(ReadMode.VISIBLE, List.of("screen"));
        reader.setLines(ReadMode.RECENT, List.of("history", "screen"));

        JsonObject visible = read(params(PANE));
        JsonObject params = params(PANE);
        params.addProperty("mode", "recent");
        JsonObject recent = read(params);

        assertThat(visible.get("mode").getAsString()).isEqualTo("visible");
        assertThat(visible.getAsJsonArray("lines")).hasSize(1);
        assertThat(recent.get("mode").getAsString()).isEqualTo("recent");
        assertThat(recent.getAsJsonArray("lines")).hasSize(2);
        assertThat(reader.reads()).containsExactly("visible:200", "recent:200").inOrder();
    }

    @Test
    void detectionAnswersFromTheAlreadyPublishedRegistrySnapshot() throws Exception {
        harness.addAgent(ControlIds.terminalViewId(TAB), ControlIds.widgetPaneIdFromPaneId(PANE),
            CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, "Do you want to proceed?");

        JsonObject params = params(PANE);
        params.addProperty("mode", "detection");
        JsonObject result = read(params);

        assertThat(result.get("detected").getAsBoolean()).isTrue();
        assertThat(result.get("kind").getAsString()).isEqualTo("claude-code");
        assertThat(result.get("state").getAsString()).isEqualTo("blocked");
        assertThat(result.get("explain").getAsString()).contains("Claude Code");
        // The detector is never re-run on a request path, so the reader is not touched at all.
        assertThat(reader.reads()).isEmpty();
    }

    @Test
    void detectionOnAnAgentLessPaneReportsNotDetectedRatherThanFailing() throws Exception {
        JsonObject params = params(PANE);
        params.addProperty("mode", "detection");

        JsonObject result = read(params);

        assertThat(result.get("detected").getAsBoolean()).isFalse();
    }

    @Test
    void linesIsClampedToTheMaximumRatherThanRefused() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("one"));
        JsonObject params = params(PANE);
        params.addProperty("mode", "recent");
        params.addProperty("lines", 50_000);

        read(params);

        assertThat(reader.reads())
            .containsExactly("recent:" + ControlApiProtocol.MAX_READ_LINES);
    }

    @Test
    void aResultOverTheByteCapDropsLeadingLinesAndSaysSo() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ControlApiProtocol.MAX_READ_LINES; i++) {
            lines.add("line-" + i + "-" + "x".repeat(80));
        }
        reader.setLines(ReadMode.RECENT, lines);
        JsonObject params = params(PANE);
        params.addProperty("mode", "recent");
        params.addProperty("lines", ControlApiProtocol.MAX_READ_LINES);

        JsonObject result = read(params);

        assertThat(result.get("truncated").getAsBoolean()).isTrue();
        assertThat(result.getAsJsonArray("lines").size()).isLessThan(lines.size());
        String last = result.getAsJsonArray("lines")
            .get(result.getAsJsonArray("lines").size() - 1).getAsString();
        assertThat(last).startsWith("line-" + (ControlApiProtocol.MAX_READ_LINES - 1) + "-");
    }

    @Test
    void aClosedReaderYieldsAnEmptyReadRatherThanAFailure() throws Exception {
        reader.setLines(ReadMode.VISIBLE, List.of("stale"));
        reader.setOpen(false);

        JsonObject result = read(params(PANE));

        assertThat(result.getAsJsonArray("lines")).isEmpty();
        assertThat(result.get("truncated").getAsBoolean()).isFalse();
    }

    @Test
    void aPaneWithoutAReadHandleIsPaneNotFound() {
        surface.addPane(FakeControlSurface.pane("p9999", TAB, "w1", 1, true, true, 4712L));

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> read(params("p9999")));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.PANE_NOT_FOUND);
    }

    @Test
    void anUnknownModeIsInvalidParams() {
        JsonObject params = params(PANE);
        params.addProperty("mode", "telepathy");

        ControlApiException failure = expectThrows(ControlApiException.class, () -> read(params));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test
    void sendKeysAcceptsTheSpaceSeparatedFormAndEchoesTheNormalisedNames() throws Exception {
        JsonObject params = params(PANE);
        params.addProperty("keys", "Ctrl-C enter");

        JsonObject result = registry
            .dispatch(session(), new ControlRequest(new JsonPrimitive(1), "pane.send_keys", params))
            .getAsJsonObject();

        assertThat(result.getAsJsonArray("keys").get(0).getAsString()).isEqualTo("ctrl+c");
        assertThat(result.getAsJsonArray("keys").get(1).getAsString()).isEqualTo("enter");
        assertThat(surface.written(PANE)).isEqualTo(new byte[] {0x03, '\r'});
    }

    @Test
    void aStaleInstanceIsRefusedBeforeAnythingIsWritten() {
        JsonObject params = params(PANE);
        params.addProperty("text", "ls");
        params.addProperty("instance", "someone-else");

        ControlApiException failure = expectThrows(ControlApiException.class, () -> registry
            .dispatch(session(), new ControlRequest(new JsonPrimitive(1), "pane.send_text", params)));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.STALE_INSTANCE);
        assertThat(surface.written(PANE)).isEmpty();
    }

    private static ControlSession session() {
        return new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test", frame -> { });
    }
}
