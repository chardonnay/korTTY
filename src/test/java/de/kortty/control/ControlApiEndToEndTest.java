package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.CodingAgentEvent;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.PaneRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The worked example of {@code stufe3-final-wire.md} §7, driven end to end over a real socket.
 *
 * <p>What this pins is the <strong>composition</strong>: the real {@link ControlApiServer} carrying
 * the real {@link ControlVerbs} table over a scripted {@link ControlSurface}, answering the seven
 * requests the specification prints — {@code auth}, {@code pane.resolve}, {@code pane.split},
 * {@code agent.start}, {@code agent.prompt} with {@code wait_until}, {@code pane.read recent} and
 * {@code pane.close} — with the exact result shapes the document names.
 *
 * <p>That contract matters because it is the one no single package can check. P2 tested framing
 * against a stub table, P3 tested the verbs against a stub dispatcher, and a field renamed on one
 * side of that line would pass both suites and break every client on the first real call. Here a
 * disagreement shows up as a missing member on a frame that actually crossed a socket.
 *
 * <p>No JavaFX toolkit is started: {@link UiDispatcher#DIRECT} runs every hop inline. The
 * unix-domain leg is skipped — never failed — when the socket path would exceed the platform limit,
 * and on Windows the same assertions run over the loopback transport instead.
 */
public class ControlApiEndToEndTest {

    /** The pane the story starts in; the shell whose pid a script would report. */
    private static final String SOURCE_PANE = "p1a2b";

    /** The pane {@code pane.split} creates and the rest of the story addresses. */
    private static final String SPLIT_PANE = "p7f31";

    private static final String TAB = "t9f3a";

    /** A pid no live process can hold, so nothing here depends on the machine's process table. */
    private static final long DEAD_PID = 4_294_967_200L;

    /**
     * How long the deliberately slow request parks the connection.
     *
     * <p>Long enough that a concurrent dispatcher would answer the request behind it far sooner, and
     * short enough that the test costs well under a second.
     */
    private static final long WAIT_MILLIS = 600L;

    private Path root;

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private final List<Thread> helpers = new ArrayList<>();

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        surface = (FakeControlSurface) ControlApiScenarioFixtures.oneLocalShellPaneWithClaudeCode();
        surface.setAttachResult(
            FakeControlSurface.pane(SPLIT_PANE, TAB, "w1", 1, true, true, 4714L));
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root, surface, agents);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServerAndDeleteTheTempTree() throws InterruptedException {
        for (Thread helper : helpers) {
            helper.join(5_000L);
        }
        helpers.clear();
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        // Closing a channel does not unlink a socket file; the server's close() does, and only then
        // is the tree removable. A leaked socket would break the next test in this shared JVM.
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void theSevenRequestStoryOfTheWireSpecificationRunsOverOneConnection() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = result(wire.authenticate(endpoint.token()), "auth");
            assertWithMessage("auth must identify the API by the name in the specification")
                .that(hello.get("api").getAsString()).isEqualTo(ControlApiProtocol.API_NAME);
            assertWithMessage("auth must publish the protocol version a client branches on")
                .that(hello.get("protocol_version").getAsInt())
                .isEqualTo(ControlApiProtocol.PROTOCOL_VERSION);
            assertWithMessage("no wire id survives a restart, and auth must say so")
                .that(hello.get("ids_survive_restart").getAsBoolean()).isFalse();
            assertWithMessage("auth must name the instance every later reply is pinned to")
                .that(hello.get("instance_id").getAsString()).isEqualTo(endpoint.instanceId());
            assertWithMessage("auth must name the transport the connection actually came in on")
                .that(hello.get("transport").getAsString()).isEqualTo(endpoint.transport());
            assertWithMessage("the four capabilities of the specification's hello must be advertised")
                .that(strings(hello.getAsJsonArray("capabilities")))
                .containsAtLeast("events", "split", "agent_start", "pane_resolve");
            assertWithMessage("auth must publish the method table so a client need not guess")
                .that(strings(hello.getAsJsonArray("methods")))
                .containsAtLeast("ping", "api.schema", "auth", "events.subscribe");

            JsonObject resolved = result(wire.call("pane.resolve",
                ControlApiScenarioFixtures.params("pids", pids(4711L, 4710L, 3901L))), "pane.resolve");
            JsonObject resolvedPane = resolved.getAsJsonObject("pane");
            assertWithMessage("pane.resolve is how a script finds the pane it runs in")
                .that(resolvedPane.get("pane_id").getAsString()).isEqualTo(SOURCE_PANE);
            assertWithMessage("pane.resolve must say which of the offered pids matched")
                .that(resolved.get("matched_pid").getAsLong()).isEqualTo(4711L);
            assertThat(resolvedPane.get("tab_id").getAsString()).isEqualTo(TAB);
            assertThat(resolvedPane.get("window_id").getAsString()).isEqualTo("w1");
            assertThat(resolvedPane.get("local_shell").getAsBoolean()).isTrue();
            assertThat(resolvedPane.get("connected").getAsBoolean()).isTrue();

            JsonObject split = result(wire.call("pane.split", ControlApiScenarioFixtures.params(
                "pane", SOURCE_PANE, "orientation", "vertical", "focus", false)), "pane.split");
            JsonObject newPane = split.getAsJsonObject("pane");
            assertWithMessage("pane.split must return the NEW pane, not the one that was split")
                .that(newPane.get("pane_id").getAsString()).isEqualTo(SPLIT_PANE);
            assertThat(newPane.get("tab_id").getAsString()).isEqualTo(TAB);
            assertThat(newPane.get("local_shell").getAsBoolean()).isTrue();
            // The new pane now exists, exactly as TerminalSplitPane would have made it exist.
            surface.addPane(FakeControlSurface.pane(SPLIT_PANE, TAB, "w1", 1, true, true, 4714L));
            FakePaneReader reader = new FakePaneReader(SPLIT_PANE);
            reader.setLines(ReadMode.RECENT,
                List.of("> summarise de.kortty.control", "the control package is…"));
            reader.setGeometry(60, 40);
            surface.setReader(SPLIT_PANE, reader);

            register(SPLIT_PANE, CodingAgentState.WORKING, "✻ Thinking…");
            JsonObject started = result(wire.call("agent.start", ControlApiScenarioFixtures.params(
                "pane", SPLIT_PANE, "kind", "claude-code")), "agent.start");
            assertThat(started.getAsJsonObject("pane").get("pane_id").getAsString())
                .isEqualTo(SPLIT_PANE);
            assertThat(started.getAsJsonObject("agent").get("kind").getAsString())
                .isEqualTo(CodingAgentKind.CLAUDE_CODE.id());
            assertWithMessage("agent.start must report the launch command it actually typed")
                .that(strings(started.getAsJsonArray("command")))
                .containsExactly(firstExecutableOf(CodingAgentKind.CLAUDE_CODE));
            assertWithMessage("agent.start is a composition over pane.run, so the command must have"
                    + " been written into the pane, not merely reported")
                .that(new String(surface.written(SPLIT_PANE), StandardCharsets.UTF_8))
                .isEqualTo(firstExecutableOf(CodingAgentKind.CLAUDE_CODE) + "\r");

            completeTheAgentShortly();
            JsonObject prompted = result(wire.call("agent.prompt", ControlApiScenarioFixtures.params(
                "pane", SPLIT_PANE, "text", "summarise de.kortty.control",
                "wait_until", "done", "timeout_ms", 30_000)), "agent.prompt");
            assertWithMessage("agent.prompt returns a WriteResult even when it also waits")
                .that(prompted.get("bytes_written").getAsInt()).isGreaterThan(0);
            assertThat(prompted.get("submitted").getAsBoolean()).isTrue();
            assertThat(prompted.get("bracketed").getAsBoolean()).isFalse();
            assertWithMessage("wait_until must add the agent.wait fields to the same result")
                .that(prompted.get("state").getAsString()).isEqualTo("done");
            assertThat(prompted.get("previous_state").getAsString()).isEqualTo("working");
            assertThat(prompted.get("waited_millis").getAsLong()).isAtLeast(0L);
            assertThat(prompted.getAsJsonObject("agent").get("state").getAsString()).isEqualTo("done");

            JsonObject text = result(wire.call("pane.read", ControlApiScenarioFixtures.params(
                "pane", SPLIT_PANE, "mode", "recent", "lines", 120)), "pane.read");
            assertThat(text.get("pane_id").getAsString()).isEqualTo(SPLIT_PANE);
            assertThat(text.get("mode").getAsString()).isEqualTo(ReadMode.RECENT.wire());
            assertThat(text.get("columns").getAsInt()).isEqualTo(60);
            assertThat(text.get("rows").getAsInt()).isEqualTo(40);
            assertThat(text.get("alternate_screen").getAsBoolean()).isFalse();
            assertWithMessage("a read well inside the result cap must not claim truncation")
                .that(text.get("truncated").getAsBoolean()).isFalse();
            assertThat(strings(text.getAsJsonArray("lines")))
                .contains("> summarise de.kortty.control");

            JsonObject closed = result(wire.call("pane.close",
                ControlApiScenarioFixtures.params("pane", SPLIT_PANE)), "pane.close");
            assertWithMessage("pane.close answers a bare acknowledgement, nothing else")
                .that(closed.keySet()).containsExactly("ok");
            assertThat(closed.get("ok").getAsBoolean()).isTrue();
        }
    }

    @Test(timeOut = 60_000)
    void everyReplyCarriesTheJsonRpcVersionAndEchoesTheRequestIdUnchanged() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = wire.authenticate(endpoint.token());
            assertThat(hello.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertThat(hello.get("id").getAsLong()).isEqualTo(wire.lastId());

            JsonObject pong = wire.call("ping", new JsonObject());
            assertThat(pong.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertWithMessage("a client correlates by id; a reply under another id is unusable")
                .that(pong.get("id").getAsLong()).isEqualTo(wire.lastId());
            JsonObject result = result(pong, "ping");
            assertThat(result.get("pong").getAsBoolean()).isTrue();
            assertThat(result.get("instance").getAsString()).isEqualTo(endpoint.instanceId());
            assertThat(result.get("protocol_version").getAsInt())
                .isEqualTo(ControlApiProtocol.PROTOCOL_VERSION);
            assertThat(result.get("app_version").getAsString()).isEqualTo(endpoint.appVersion());
            assertThat(result.get("uptime_millis").getAsLong()).isAtLeast(0L);
        }
    }

    @Test(timeOut = 60_000)
    void everyEnumerationCarriesTheInstanceTheIdsBelongTo() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            for (String method : List.of("window.list", "tab.list", "pane.list")) {
                JsonObject listed = result(wire.call(method, new JsonObject()), method);
                assertWithMessage("%s must pin its ids to an instance, because nothing survives a"
                        + " restart and a client re-enumerates when the instance changes", method)
                    .that(listed.get("instance").getAsString()).isEqualTo(endpoint.instanceId());
            }
        }
    }

    @Test(timeOut = 60_000)
    void threeRequestsSentAtOnceAreAnsweredSequentiallyAndInOrder() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(10L, "ping", new JsonObject()));
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(11L, "window.list", new JsonObject()));
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(12L, "pane.current", new JsonObject()));

            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ids.add(wire.next().get("id").getAsLong());
            }
            assertWithMessage("one request at a time per connection: the replies must come back in"
                    + " the order the requests were read, or a client cannot pipeline at all")
                .that(ids).containsExactly(10L, 11L, 12L).inOrder();
        }
    }

    @Test(timeOut = 60_000)
    void aBlockingRequestHoldsTheConnectionUntilItIsAnsweredAndTheNextOneWaits() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            // Both lines are in the socket buffer before the server has answered either. §2: "the
            // next line is read only after the previous response has been queued" — which is the
            // whole reason §8 tells a client to open a second connection for a long wait plus
            // concurrent calls. Three instantaneous verbs cannot tell that apart from a dispatcher
            // that hands every parsed request to a pool, so one of these two is slow on purpose.
            long sentAtNanos = System.nanoTime();
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(20L, "pane.wait_output",
                ControlApiScenarioFixtures.params("pane", SOURCE_PANE,
                    "contains", "this output never appears", "timeout_ms", WAIT_MILLIS,
                    "poll_ms", 50)));
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(21L, "ping", new JsonObject()));

            JsonObject waited = wire.next();
            assertWithMessage("the wait was read first, so it must be answered first").that(waited)
                .isNotNull();
            assertThat(waited.get("id").getAsLong()).isEqualTo(20L);
            assertWithMessage("a wait that never matches ends in timeout, not in a result")
                .that(waited.getAsJsonObject("error").getAsJsonObject("data").get("code")
                    .getAsString()).isEqualTo(ControlErrorCode.TIMEOUT.wire());

            JsonObject pong = wire.next();
            long elapsedMillis = (System.nanoTime() - sentAtNanos) / 1_000_000L;
            assertThat(pong.get("id").getAsLong()).isEqualTo(21L);
            assertThat(result(pong, "ping").get("pong").getAsBoolean()).isTrue();
            assertWithMessage("the ping was sent immediately but must not be served until the %s ms"
                    + " wait ahead of it is done; it came back after %s ms, which means the two"
                    + " requests ran concurrently and the sequential-execution promise §8 publishes"
                    + " is not kept", WAIT_MILLIS, elapsedMillis)
                .that(elapsedMillis).isAtLeast(WAIT_MILLIS / 2L);
        }
    }

    @Test(timeOut = 60_000)
    void anyMethodBeforeAuthIsUnauthorisedAndTheConnectionIsClosed() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject refused = wire.call("pane.list", new JsonObject());
            JsonObject error = refused.getAsJsonObject("error");
            assertWithMessage("the first request on a connection must be auth")
                .that(error.getAsJsonObject("data").get("code").getAsString())
                .isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
            assertWithMessage("an unauthenticated client must not be left a usable connection")
                .that(wire.next()).isNull();
        }
    }

    @Test(timeOut = 60_000)
    void aWrongTokenIsRefusedOnceAndTheConnectionIsClosed() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject refused = wire.authenticate("not-the-token-but-long-enough-to-be-compared");
            assertThat(refused.getAsJsonObject("error").getAsJsonObject("data").get("code")
                .getAsString()).isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
            assertWithMessage("one failure closes the connection; there is nothing to brute-force")
                .that(wire.next()).isNull();
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** The result object of a reply, or a readable failure naming the error the server sent. */
    private static JsonObject result(JsonObject frame, String method) {
        assertWithMessage("%s must have produced a reply", method).that(frame).isNotNull();
        if (frame.has("error")) {
            throw new AssertionError(method + " failed on the wire: " + frame.get("error"));
        }
        return frame.getAsJsonObject("result");
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> values.add(element.getAsString()));
        }
        return values;
    }

    private static JsonArray pids(long... values) {
        JsonArray array = new JsonArray();
        for (long value : values) {
            array.add(value);
        }
        return array;
    }

    /** The launch command {@code agent.start} derives, read from the enum rather than hard-coded. */
    private static String firstExecutableOf(CodingAgentKind kind) {
        return kind.executableNames().stream().sorted().findFirst().orElseThrow();
    }

    private void register(String paneId, CodingAgentState state, String evidence) {
        PaneRef ref = ControlApiScenarioFixtures.paneRef(TAB, paneId);
        agents.onEvent(new CodingAgentEvent(ref, DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", evidence),
            new AgentProcess(DEAD_PID, CodingAgentKind.CLAUDE_CODE, "claude", null),
            CodingAgentEvent.Reason.DETECTED, Instant.now()));
    }

    /**
     * Moves the agent from WORKING to DONE shortly after the prompt goes out.
     *
     * <p>The waiter reads the current entry and subscribes inside one block, so this transition is
     * observed whether it lands before or after the subscription: there is no race to lose.
     */
    private void completeTheAgentShortly() {
        PaneRef ref = ControlApiScenarioFixtures.paneRef(TAB, SPLIT_PANE);
        Thread helper = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            agents.onEvent(new CodingAgentEvent(ref,
                DetectionResult.of(CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING,
                    "claude.rule", "✻ Thinking…"),
                DetectionResult.of(CodingAgentKind.CLAUDE_CODE, CodingAgentState.DONE,
                    "claude.done", "Done."),
                new AgentProcess(DEAD_PID, CodingAgentKind.CLAUDE_CODE, "claude", null),
                CodingAgentEvent.Reason.STATE_CHANGED, Instant.now()));
        }, "kt-fixture-agent-completion");
        helper.setDaemon(true);
        helpers.add(helper);
        helper.start();
    }
}
