package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class AgentStartServiceTest {

    private static final String PANE = "p1a2b";

    private static final String CREATED = "p7f31";

    private final AtomicLong clock = new AtomicLong(100_000L);

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ScheduledExecutorService timer;

    private AgentStartService starter;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, "t1", "w1", 0, true, true, 4711L));
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        timer = new ScheduledThreadPoolExecutor(1);
        ControlPaneWriter writer =
            new ControlPaneWriter(surface, UiDispatcher.DIRECT, (verb, pane, detail) -> { });
        ControlSplitService splits =
            new ControlSplitService(surface, UiDispatcher.DIRECT, (verb, pane, detail) -> { });
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        ControlAgentGateway gateway =
            new ControlAgentGateway(surface, UiDispatcher.DIRECT, agents, actions);
        AgentStateWaiter waiter =
            new AgentStateWaiter(surface, UiDispatcher.DIRECT, agents, timer);
        starter = new AgentStartService(splits, writer, gateway, waiter, surface);
    }

    @AfterMethod
    void tearDown() {
        timer.shutdownNow();
        agents.clear();
    }

    private static PaneRef ref(String paneId) {
        return new PaneRef(ControlIds.terminalViewId("t1"), ControlIds.widgetPaneIdFromPaneId(paneId));
    }

    private void register(String paneId, CodingAgentKind kind) {
        agents.onEvent(new CodingAgentEvent(ref(paneId), DetectionResult.NONE,
            DetectionResult.of(kind, CodingAgentState.IDLE, "rule", "ready"),
            new AgentProcess(4711L, kind, "agent", null),
            CodingAgentEvent.Reason.DETECTED, java.time.Instant.ofEpochMilli(clock.get())));
    }

    private static AgentStartService.Request request(String paneId, String splitFrom) {
        return new AgentStartService.Request(paneId, splitFrom, "horizontal", "claude-code",
            List.of(), null, 2_000L, false, "done", 2_000L);
    }

    @Test(timeOut = 30_000)
    void thePaneFormNeverSplitsAndTypesTheKindsOwnExecutable() throws Exception {
        timer.schedule(() -> register(PANE, CodingAgentKind.CLAUDE_CODE), 50L, TimeUnit.MILLISECONDS);

        AgentStartService.Result result = starter.start(request(PANE, null));

        assertThat(result.command()).containsExactly("claude");
        assertThat(result.pane()).isNull();
        assertThat(result.agent().kind()).isEqualTo("claude-code");
        assertThat(new String(surface.written(PANE), StandardCharsets.UTF_8)).isEqualTo("claude\r");
        for (String call : surface.calls()) {
            assertThat(call).doesNotContain("prepareLocalShellSplitConnector");
        }
    }

    @Test(timeOut = 30_000)
    void theSplitFormCreatesThePaneFirstAndReportsIt() throws Exception {
        PaneInfo created = FakeControlSurface.pane(CREATED, "t1", "w1", 1, true, true, 4712L);
        surface.setAttachResult(created);
        surface.addPane(created);
        timer.schedule(() -> register(CREATED, CodingAgentKind.CLAUDE_CODE), 50L, TimeUnit.MILLISECONDS);

        AgentStartService.Result result = starter.start(request(null, PANE));

        assertThat(result.pane()).isEqualTo(created);
        assertThat(result.agent().paneId()).isEqualTo(CREATED);
        assertThat(new String(surface.written(CREATED), StandardCharsets.UTF_8)).isEqualTo("claude\r");
    }

    @Test(timeOut = 30_000)
    void anExplicitCommandOverridesTheDefaultExecutable() throws Exception {
        timer.schedule(() -> register(PANE, CodingAgentKind.CLAUDE_CODE), 50L, TimeUnit.MILLISECONDS);
        AgentStartService.Request request = new AgentStartService.Request(PANE, null, "horizontal",
            "claude-code", List.of("npx", "claude"), null, 2_000L, false, "done", 2_000L);

        AgentStartService.Result result = starter.start(request);

        assertThat(result.command()).containsExactly("npx", "claude").inOrder();
        assertThat(new String(surface.written(PANE), StandardCharsets.UTF_8)).isEqualTo("npx claude\r");
    }

    @Test(timeOut = 30_000)
    void aPaneThatNeverConnectsTimesOutAtTheConnectStage() {
        surface.setPaneConnected(false);
        AgentStartService.Request request = new AgentStartService.Request(PANE, null, "horizontal",
            "claude-code", List.of(), null, 200L, false, "done", 200L);

        ControlApiException failure = expectThrows(ControlApiException.class, () -> starter.start(request));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(failure.data()).containsEntry("stage", "connect");
        assertThat(surface.written(PANE)).isEmpty();
    }

    @Test(timeOut = 30_000)
    void anAgentThatIsNeverDetectedTimesOutAtTheDetectStage() {
        AgentStartService.Request request = new AgentStartService.Request(PANE, null, "horizontal",
            "claude-code", List.of(), null, 300L, false, "done", 300L);

        ControlApiException failure = expectThrows(ControlApiException.class, () -> starter.start(request));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(failure.data()).containsEntry("stage", "detect");
    }

    @Test(timeOut = 30_000)
    void anAgentOfAnotherKindDoesNotSatisfyTheDetectWait() {
        register(PANE, CodingAgentKind.CODEX);
        AgentStartService.Request request = new AgentStartService.Request(PANE, null, "horizontal",
            "claude-code", List.of(), null, 300L, false, "done", 300L);

        ControlApiException failure = expectThrows(ControlApiException.class, () -> starter.start(request));

        assertThat(failure.data()).containsEntry("stage", "detect");
    }

    @Test(timeOut = 30_000)
    void exactlyOneOfPaneAndSplitFromIsRequired() {
        assertThat(expectThrows(ControlApiException.class,
            () -> starter.start(request(null, null))).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> starter.start(request(PANE, PANE))).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
    }

    @Test(timeOut = 30_000)
    void anUnknownKindIsInvalidParamsBeforeAnythingIsTyped() {
        AgentStartService.Request request = new AgentStartService.Request(PANE, null, "horizontal",
            "cursor", List.of(), null, 300L, false, "done", 300L);

        ControlApiException failure = expectThrows(ControlApiException.class, () -> starter.start(request));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(surface.written(PANE)).isEmpty();
    }
}
