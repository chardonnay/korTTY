package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
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
import de.kortty.core.SessionJournalRedactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What an MCP client ({@code client_kind = "mcp"}) gets back from the read verbs: masked with the
 * pane's known secrets and the token formats, capped at 2000 rows and 64k characters, with the
 * masked count reported — while a CLI client keeps reading the raw text exactly as before.
 */
class PaneReadMcpRedactionTest {

    private static final String PANE = "p1a2b";

    private static final String TAB = "t1";

    private static final String PASSWORD = "hunter2-Sup3rSecret";

    /** Split so no scanner mistakes the fixture for a leaked credential. */
    private static final String AWS_KEY_ID = "AKIA" + "ABCDEFGHIJKLMNOP";

    private static final String MASK = SessionJournalRedactor.REPLACEMENT;

    private FakeControlSurface surface;

    private FakePaneReader reader;

    private CodingAgentRegistry agents;

    private ScheduledExecutorService timer;

    private ControlEventBus events;

    private MethodRegistry registry;

    private final AtomicLong clock = new AtomicLong(100_000L);

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, TAB, "w1", 0, true, true, 4711L));
        reader = new FakePaneReader(PANE);
        surface.setReader(PANE, reader);
        SessionJournalRedactor secrets = new SessionJournalRedactor();
        secrets.addSecret(PASSWORD);
        surface.setSecrets(PANE, secrets);
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        FakePaneAccess panes = new FakePaneAccess();
        panes.connect(ref(PANE));
        timer = new ScheduledThreadPoolExecutor(1);
        events = new ControlEventBus(timer, clock::get);
        CodingAgentActions actions = new CodingAgentActions(agents, panes, (verb, pane, detail) -> { });
        registry = ControlVerbs.build(surface, UiDispatcher.DIRECT, agents, actions, events,
            (verb, pane, detail) -> { }, null, clock::get, "3.4.1", "test-instance");
    }

    @AfterMethod
    void tearDown() {
        events.closeAll();
        timer.shutdownNow();
        agents.clear();
    }

    private static PaneRef ref(String paneId) {
        return new PaneRef(ControlIds.terminalViewId(TAB), ControlIds.widgetPaneIdFromPaneId(paneId));
    }

    private static ControlSession session(ControlSession.ClientKind kind) {
        return new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test", kind, frame -> { });
    }

    private JsonObject call(ControlSession.ClientKind kind, String method, JsonObject params)
            throws Exception {
        JsonElement result = registry.dispatch(session(kind),
            new ControlRequest(new JsonPrimitive(1), method, params));
        return result.getAsJsonObject();
    }

    private static JsonObject params(String pane) {
        JsonObject params = new JsonObject();
        params.addProperty("pane", pane);
        return params;
    }

    private static List<String> lines(JsonObject result) {
        List<String> out = new ArrayList<>();
        for (JsonElement line : result.getAsJsonArray("lines")) {
            out.add(line.getAsString());
        }
        return out;
    }

    private void registerAgent(String command, String evidence) {
        agents.onEvent(new CodingAgentEvent(ref(PANE), DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING, "claude.rule", evidence),
            new AgentProcess(4242L, CodingAgentKind.CLAUDE_CODE, command, null),
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(clock.get())));
    }

    @Test
    void theConnectionPasswordAndATokenAreMaskedForMcpAndRawForCli() throws Exception {
        reader.setLines(ReadMode.VISIBLE, List.of("$ echo " + PASSWORD, "key " + AWS_KEY_ID, "plain"));

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.read", params(PANE));
        JsonObject cli = call(ControlSession.ClientKind.CLI, "pane.read", params(PANE));

        assertThat(lines(mcp)).containsExactly("$ echo " + MASK, "key AKIA" + MASK, "plain").inOrder();
        assertThat(mcp.toString()).doesNotContain(PASSWORD);
        assertThat(mcp.toString()).doesNotContain(AWS_KEY_ID);
        assertThat(mcp.get(McpOutputMasking.FIELD_MASKED_COUNT).getAsInt()).isEqualTo(2);
        assertThat(lines(cli)).containsExactly("$ echo " + PASSWORD, "key " + AWS_KEY_ID, "plain").inOrder();
        assertThat(cli.has(McpOutputMasking.FIELD_MASKED_COUNT)).isFalse();
    }

    @Test
    void aPaneWithoutAKnownSessionStillHasItsTokensMasked() throws Exception {
        surface.setSecrets(PANE, null);
        reader.setLines(ReadMode.VISIBLE, List.of("key " + AWS_KEY_ID));

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.read", params(PANE));

        assertThat(lines(mcp)).containsExactly("key AKIA" + MASK);
        assertThat(mcp.get(McpOutputMasking.FIELD_MASKED_COUNT).getAsInt()).isEqualTo(1);
    }

    @Test
    void anMcpReadIsCappedAtTwoThousandRowsWhileTheCliKeepsTheProtocolCap() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            many.add("row " + i);
        }
        reader.setLines(ReadMode.RECENT, many);
        JsonObject params = params(PANE);
        params.addProperty("mode", "recent");
        params.addProperty("lines", 10_000);

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.read", params);
        JsonObject cli = call(ControlSession.ClientKind.CLI, "pane.read", params);

        assertThat(reader.reads()).containsExactly("recent:" + McpOutputMasking.MAX_LINES,
            "recent:" + ControlApiProtocol.MAX_READ_LINES).inOrder();
        assertThat(lines(mcp)).hasSize(McpOutputMasking.MAX_LINES);
        assertThat(lines(mcp).get(McpOutputMasking.MAX_LINES - 1)).isEqualTo("row 4999");
        assertThat(lines(cli)).hasSize(5_000);
    }

    @Test
    void anMcpReadIsCappedAtSixtyFourThousandCharactersKeepingTheNewestRows() throws Exception {
        String wide = "x".repeat(199);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            rows.add(wide);
        }
        rows.add("newest " + PASSWORD);
        reader.setLines(ReadMode.RECENT, rows);
        JsonObject params = params(PANE);
        params.addProperty("mode", "recent");
        params.addProperty("lines", 2_000);

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.read", params);

        List<String> got = lines(mcp);
        long chars = got.stream().mapToLong(line -> line.length() + 1L).sum();
        assertThat(chars).isAtMost((long) McpOutputMasking.MAX_CHARS);
        assertThat(got.get(got.size() - 1)).isEqualTo("newest " + MASK);
        assertThat(mcp.get("truncated").getAsBoolean()).isTrue();
        assertThat(mcp.get(McpOutputMasking.FIELD_MASKED_COUNT).getAsInt()).isEqualTo(1);
    }

    @Test
    void aSingleRowWiderThanTheBudgetKeepsItsMaskedTail() throws Exception {
        reader.setLines(ReadMode.VISIBLE, List.of(PASSWORD + "y".repeat(100_000) + " " + PASSWORD));

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.read", params(PANE));

        List<String> got = lines(mcp);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).length()).isLessThan(McpOutputMasking.MAX_CHARS);
        assertThat(got.get(0)).endsWith(" " + MASK);
        assertThat(got.get(0)).doesNotContain(PASSWORD.substring(0, 6));
    }

    @Test
    void aSecretThatWrapsAcrossFullWidthRowsIsStillMasked() {
        SessionJournalRedactor secrets = new SessionJournalRedactor();
        secrets.addSecret(PASSWORD);
        // A 12-column pane: the password and the access key both run over the right edge.
        String passwordLine = "pw: " + PASSWORD;
        String keyLine = "k: " + AWS_KEY_ID;
        List<String> rows = List.of(passwordLine.substring(0, 12), passwordLine.substring(12),
            keyLine.substring(0, 12), keyLine.substring(12), "short row", "exactly12chr", "tail");
        PaneText source = new PaneText(PANE, ReadMode.RECENT.wire(), rows, 12, 24, false, null, false);

        McpOutputMasking masking = McpOutputMasking.with(secrets);
        PaneText masked = masking.text(source);

        String all = String.join("", masked.lines());
        assertThat(all).doesNotContain(PASSWORD.substring(0, 8));
        assertThat(all).doesNotContain(PASSWORD.substring(PASSWORD.length() - 8));
        assertThat(all).doesNotContain(AWS_KEY_ID.substring(4));
        assertThat(all).contains(MASK);
        assertThat(masking.maskedCount()).isAtLeast(2);
        for (String line : masked.lines()) {
            assertWithMessage("re-wrapped rows stay within the pane width").that(line.length()).isAtMost(12);
        }
        assertWithMessage("rows without anything to mask keep their exact layout")
            .that(masked.lines().subList(masked.lines().size() - 3, masked.lines().size()))
            .containsExactly("short row", "exactly12chr", "tail").inOrder();
    }

    @Test
    void waitOutputMatchesAndReturnsMaskedTextForMcp() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("login ok " + PASSWORD, "$ "));
        JsonObject params = params(PANE);
        params.addProperty("contains", "login ok");
        params.addProperty("timeout_ms", 2_000);

        JsonObject mcp = call(ControlSession.ClientKind.MCP, "pane.wait_output", params);
        JsonObject cli = call(ControlSession.ClientKind.CLI, "pane.wait_output", params);

        assertThat(mcp.get("line").getAsString()).isEqualTo("login ok " + MASK);
        assertThat(mcp.toString()).doesNotContain(PASSWORD);
        assertThat(cli.get("line").getAsString()).isEqualTo("login ok " + PASSWORD);
    }

    @Test
    void anMcpClientCannotConfirmAGuessedPasswordThroughWaitOutput() throws Exception {
        reader.setLines(ReadMode.RECENT, List.of("login ok " + PASSWORD));
        JsonObject params = params(PANE);
        params.addProperty("contains", PASSWORD);
        params.addProperty("timeout_ms", 300);
        params.addProperty("poll_ms", 100);

        ControlApiException refused = expectThrows(ControlApiException.class,
            () -> call(ControlSession.ClientKind.MCP, "pane.wait_output", params));

        assertThat(refused.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(String.valueOf(refused.data())).doesNotContain(PASSWORD);
        assertThat(call(ControlSession.ClientKind.CLI, "pane.wait_output", params)
            .get("matched").getAsBoolean()).isTrue();
    }

    @Test
    void agentCommandLineSecretsAreMaskedForMcpInEveryReadThatCarriesThem() throws Exception {
        String command = "claude --api-key " + AWS_KEY_ID + " --password " + PASSWORD;
        registerAgent(command, "Thinking with " + PASSWORD);

        JsonObject listed = call(ControlSession.ClientKind.MCP, "agent.list", new JsonObject())
            .getAsJsonArray("agents").get(0).getAsJsonObject();
        JsonObject got = call(ControlSession.ClientKind.MCP, "agent.get", params(PANE))
            .getAsJsonObject("agent");
        JsonObject params = params(PANE);
        params.addProperty("mode", "detection");
        JsonObject detection = call(ControlSession.ClientKind.MCP, "pane.read", params);
        JsonObject cli = call(ControlSession.ClientKind.CLI, "agent.get", params(PANE))
            .getAsJsonObject("agent");

        for (JsonObject agent : List.of(listed, got)) {
            assertThat(agent.get("command").getAsString()).doesNotContain(AWS_KEY_ID);
            assertThat(agent.get("command").getAsString()).doesNotContain(PASSWORD);
            assertThat(agent.get("command").getAsString()).startsWith("claude --api-key ");
            assertThat(agent.get("evidence").getAsString()).isEqualTo("Thinking with " + MASK);
        }
        assertThat(detection.get("evidence").getAsString()).isEqualTo("Thinking with " + MASK);
        assertThat(detection.toString()).doesNotContain(PASSWORD);
        assertThat(detection.get(McpOutputMasking.FIELD_MASKED_COUNT).getAsInt()).isAtLeast(1);
        assertThat(cli.get("command").getAsString()).isEqualTo(command);
    }

    @Test
    void paneDescriptionsAndTabTitlesAreMaskedForMcp() throws Exception {
        String command = "codex --token " + AWS_KEY_ID;
        AgentInfo agent = new AgentInfo("p9", TAB, "w1", true, "codex", "working", "Codex", null, null,
            "evidence", 0L, 0L, false, 7L, command);
        surface.addPane(new PaneInfo("p9", TAB, "w1", 1, false, "LOCAL_SHELL", true, true, "/home/u", 1L,
            80, 24, false, false, agent));
        surface.addTab(new TabInfo(TAB, "w1", "deploy " + AWS_KEY_ID, "SSH", "host", true, true, 2,
            new AgentRollupInfo(0, 0, 0, 0, 0, null)));

        JsonArray panes = call(ControlSession.ClientKind.MCP, "pane.list", new JsonObject())
            .getAsJsonArray("panes");
        JsonObject tab = call(ControlSession.ClientKind.MCP, "tab.list", new JsonObject())
            .getAsJsonArray("tabs").get(0).getAsJsonObject();
        JsonArray cliPanes = call(ControlSession.ClientKind.CLI, "pane.list", new JsonObject())
            .getAsJsonArray("panes");

        assertThat(panes.toString()).doesNotContain(AWS_KEY_ID);
        assertThat(tab.get("title").getAsString()).isEqualTo("deploy AKIA" + MASK);
        assertThat(cliPanes.toString()).contains(AWS_KEY_ID);
    }

    @Test
    void theSecretsAreLookedUpOnlyForMcpClients() throws Exception {
        reader.setLines(ReadMode.VISIBLE, List.of("plain"));

        call(ControlSession.ClientKind.CLI, "pane.read", params(PANE));
        assertThat(surface.calls().stream().anyMatch(c -> c.startsWith("secretRedactorFor"))).isFalse();

        call(ControlSession.ClientKind.MCP, "pane.read", params(PANE));
        assertThat(surface.calls().stream().anyMatch(c -> c.startsWith("secretRedactorFor"))).isTrue();
    }
}
