package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentEvent;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.FakePaneAccess;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RecordingTtyConnector;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class AgentVerbsTest {

    /** A pid no process can hold, so {@code AgentProcess.isAlive()} would answer false for it. */
    private static final long DEAD_PID = 4_294_967_200L;

    private static final String BLOCKED_PANE = "pblocked";

    private static final String WORKING_PANE = "pworking";

    private static final String BARE_PANE = "pbare";

    private FakeControlSurface surface;

    private ThreadRecordingDispatcher ui;

    private CodingAgentRegistry agents;

    private FakePaneAccess panes;

    private ScheduledExecutorService timer;

    private ControlEventBus events;

    /** Every audit line the verb table produced, as {@code verb pane detail}. */
    private List<String> audit;

    private MethodRegistry registry;

    private final AtomicLong clock = new AtomicLong(100_000L);

    /** Records the thread every dispatched task ran on, so a missed hop is visible. */
    private static final class ThreadRecordingDispatcher implements UiDispatcher {

        private final List<String> threads = new ArrayList<>();

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            threads.add(Thread.currentThread().getName());
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public boolean isUiThread() {
            return true;
        }
    }

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(BLOCKED_PANE, "t1", "w1", 0, true, true, 4711L));
        surface.addPane(FakeControlSurface.pane(WORKING_PANE, "t1", "w1", 1, true, true, 4712L));
        surface.addPane(FakeControlSurface.pane(BARE_PANE, "t1", "w1", 2, true, true, 4713L));
        ui = new ThreadRecordingDispatcher();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        panes = new FakePaneAccess();
        panes.connect(ref(BLOCKED_PANE));
        panes.connect(ref(WORKING_PANE));
        timer = new ScheduledThreadPoolExecutor(1);
        events = new ControlEventBus(timer, clock::get);
        audit = new ArrayList<>();
        ControlAuditSink sink = (verb, pane, detail) -> audit.add(verb + " " + pane + " " + detail);
        CodingAgentActions actions =
            new CodingAgentActions(agents, panes, ControlApiWiring.agentAuditSink(sink));
        registry = ControlVerbs.build(surface, ui, agents, actions, events,
            sink, null, clock::get, "3.4.1", "test-instance");
    }

    @AfterMethod
    void tearDown() {
        events.closeAll();
        timer.shutdownNow();
        agents.clear();
    }

    private static PaneRef ref(String paneId) {
        return new PaneRef(ControlIds.terminalViewId("t1"), ControlIds.widgetPaneIdFromPaneId(paneId));
    }

    /** Registers an agent whose process pid is dead, so a projection that used isAlive would drop it. */
    private void register(String paneId, CodingAgentState state, String evidence) {
        agents.onEvent(new CodingAgentEvent(ref(paneId), DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", evidence),
            new AgentProcess(DEAD_PID, CodingAgentKind.CLAUDE_CODE, "claude", null),
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(clock.get())));
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        return registry.dispatch(session(), new ControlRequest(new JsonPrimitive(1), method, params))
            .getAsJsonObject();
    }

    private static JsonObject params(String pane) {
        JsonObject params = new JsonObject();
        params.addProperty("pane", pane);
        return params;
    }

    @Test
    void everyVerbOnAPaneWithoutARegistryEntryIsAgentNotFound() {
        JsonObject prompt = params(BARE_PANE);
        prompt.addProperty("text", "hello");
        JsonObject keys = params(BARE_PANE);
        keys.addProperty("keys", "enter");
        JsonObject rename = params(BARE_PANE);
        rename.addProperty("alias", "backend");
        JsonObject wait = params(BARE_PANE);
        wait.addProperty("until", "done");
        wait.addProperty("timeout_ms", 200);

        assertCode("agent.get", params(BARE_PANE), ControlErrorCode.AGENT_NOT_FOUND);
        assertCode("agent.explain", params(BARE_PANE), ControlErrorCode.AGENT_NOT_FOUND);
        assertCode("agent.prompt", prompt, ControlErrorCode.AGENT_NOT_FOUND);
        assertCode("agent.send_keys", keys, ControlErrorCode.AGENT_NOT_FOUND);
        assertCode("agent.rename", rename, ControlErrorCode.AGENT_NOT_FOUND);
        assertCode("agent.wait", wait, ControlErrorCode.AGENT_NOT_FOUND);
    }

    private void assertCode(String method, JsonObject params, ControlErrorCode expected) {
        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> call(method, params));
        assertThat(failure.code()).isEqualTo(expected);
    }

    @Test
    void promptingABlockedAgentIsRefusedSoTheDecisionIsAnsweredFirst() {
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");
        JsonObject params = params(BLOCKED_PANE);
        params.addProperty("text", "carry on");

        assertCode("agent.prompt", params, ControlErrorCode.AGENT_BLOCKED);
        RecordingTtyConnector connector =
            (RecordingTtyConnector) panes.connectorFor(ref(BLOCKED_PANE)).orElseThrow();
        assertThat(connector.written()).isEmpty();
    }

    @Test
    void promptingAnIdleAgentSubmitsItAndReportsTheByteCount() throws Exception {
        register(WORKING_PANE, CodingAgentState.IDLE, "waiting");
        JsonObject params = params(WORKING_PANE);
        params.addProperty("text", "summarise");

        JsonObject result = call("agent.prompt", params);

        RecordingTtyConnector connector =
            (RecordingTtyConnector) panes.connectorFor(ref(WORKING_PANE)).orElseThrow();
        assertThat(connector.writtenText()).isEqualTo("summarise\r");
        assertThat(result.get("bytes_written").getAsInt()).isEqualTo(10);
        assertThat(result.get("submitted").getAsBoolean()).isTrue();
    }

    @Test
    void sendKeysDelegatesTheChordVocabularyToTheStageTwoActions() throws Exception {
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");
        JsonObject params = params(BLOCKED_PANE);
        params.addProperty("keys", "y enter");

        JsonObject result = call("agent.send_keys", params);

        RecordingTtyConnector connector =
            (RecordingTtyConnector) panes.connectorFor(ref(BLOCKED_PANE)).orElseThrow();
        assertThat(connector.writtenText()).isEqualTo("y\r");
        assertThat(result.getAsJsonArray("keys").toString()).isEqualTo("[\"y\",\"enter\"]");
    }

    @Test
    void sendKeysOutsideTheChordVocabularyFallsBackToTheControlKeyTable() throws Exception {
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");
        JsonObject params = params(BLOCKED_PANE);
        params.addProperty("keys", "pageup");

        JsonObject result = call("agent.send_keys", params);

        assertThat(result.getAsJsonArray("keys").toString()).isEqualTo("[\"pageup\"]");
        assertThat(new String(surface.written(BLOCKED_PANE), StandardCharsets.UTF_8))
            .isEqualTo("\u001b[5~");
    }

    /**
     * Why: the fallback branch writes straight through the surface instead of through
     * {@code CodingAgentActions}, so it owes its own audit line. It is also the branch that carries
     * an arbitrary payload — every printable character is in the key table but in no {@code KeyChord}
     * — which makes an unaudited write here exactly the one that matters.
     */
    @Test
    void everySendKeysBranchLeavesExactlyOneAuditLine() throws Exception {
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");
        JsonObject chords = params(BLOCKED_PANE);
        chords.addProperty("keys", "y enter");
        JsonObject wide = params(BLOCKED_PANE);
        wide.addProperty("keys", "c u r l enter");

        call("agent.send_keys", chords);
        call("agent.send_keys", wide);

        assertThat(audit).hasSize(2);
        for (String line : audit) {
            assertThat(line).startsWith("agent.send_keys " + BLOCKED_PANE + " ");
        }
    }

    /** Why: a prompt types arbitrary bytes into someone else's agent; it may never write unrecorded. */
    @Test
    void promptIsAuditedUnderItsWireVerb() throws Exception {
        register(WORKING_PANE, CodingAgentState.IDLE, "waiting");
        JsonObject params = params(WORKING_PANE);
        params.addProperty("text", "summarise");

        call("agent.prompt", params);

        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).startsWith("agent.prompt " + WORKING_PANE + " bytes=");
    }

    @Test
    void renameIsDispatchedThroughTheUiDispatcherBecauseAnOffThreadAliasIsDroppedSilently()
            throws Exception {
        register(WORKING_PANE, CodingAgentState.WORKING, "thinking");
        ui.threads.clear();
        JsonObject params = params(WORKING_PANE);
        params.addProperty("alias", "backend");

        JsonObject result = call("agent.rename", params);

        assertThat(result.getAsJsonObject("agent").get("alias").getAsString()).isEqualTo("backend");
        assertThat(ui.threads).isNotEmpty();
        for (String thread : ui.threads) {
            assertThat(thread).isEqualTo(Thread.currentThread().getName());
        }
        assertThat(agents.entry(ref(WORKING_PANE)).orElseThrow().alias()).isEqualTo("backend");
    }

    @Test
    void listPutsBlockedFirstAndThenTheLongestInState() throws Exception {
        register(WORKING_PANE, CodingAgentState.WORKING, "thinking");
        clock.addAndGet(5_000L);
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");

        JsonArray listed = call("agent.list", new JsonObject()).getAsJsonArray("agents");

        List<String> paneIds = new ArrayList<>();
        for (JsonElement agent : listed) {
            paneIds.add(agent.getAsJsonObject().get("pane_id").getAsString());
        }
        assertThat(paneIds).containsExactly(BLOCKED_PANE, WORKING_PANE).inOrder();
    }

    @Test
    void listFiltersByStateAndByKind() throws Exception {
        register(WORKING_PANE, CodingAgentState.WORKING, "thinking");
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");

        JsonObject byState = new JsonObject();
        byState.addProperty("state", "blocked");
        assertThat(call("agent.list", byState).getAsJsonArray("agents")).hasSize(1);

        JsonObject byKind = new JsonObject();
        byKind.addProperty("kind", "codex");
        assertThat(call("agent.list", byKind).getAsJsonArray("agents")).isEmpty();

        assertThat(call("agent.list", new JsonObject()).getAsJsonObject("totals")
            .get("total").getAsInt()).isEqualTo(2);
    }

    @Test
    void anAgentWhoseProcessIsNoLongerAliveIsStillListedBecauseIsAliveIsNeverConsulted()
            throws Exception {
        register(WORKING_PANE, CodingAgentState.WORKING, "thinking");
        AgentProcess process = agents.entry(ref(WORKING_PANE)).orElseThrow().process();
        assertThat(process.isAlive()).isFalse();

        JsonArray listed = call("agent.list", new JsonObject()).getAsJsonArray("agents");

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getAsJsonObject().get("pid").getAsLong()).isEqualTo(DEAD_PID);
        assertThat(listed.get(0).getAsJsonObject().get("detected").getAsBoolean()).isTrue();
    }

    @Test
    void explainDescribesTheDetectionWithoutRerunningIt() throws Exception {
        register(BLOCKED_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");

        String explain = call("agent.explain", params(BLOCKED_PANE)).get("explain").getAsString();

        assertThat(explain).contains("Claude Code");
        assertThat(explain).contains("claude.rule");
    }

    private static ControlSession session() {
        return new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test", frame -> { });
    }
}
