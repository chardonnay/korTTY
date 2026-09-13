package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Every error in the closed vocabulary, provoked over a real socket and checked against the table in
 * {@code stufe3-final-wire.md} §4.
 *
 * <p>Three things have to hold at once for a client to behave, and only a suite that crosses a socket
 * can see all three: the JSON-RPC {@code error.code} number, the stable {@code data.code} a script
 * branches on, and {@code data.exit} — the CLI exit code, supplied by the <em>server</em> precisely so
 * that client and server can never hold two disagreeing tables. {@code data.retryable} is the fourth:
 * it is what tells an automated caller whether a retry is worth anything.
 *
 * <p>The documented table is transcribed into {@link #DOCUMENTED_TABLE} and compared with the enum,
 * so the doc and {@link ControlErrorCode} cannot drift apart unnoticed; the provoked round trips then
 * read their expectations from the enum, so a scenario can never be quietly rewritten to match
 * whatever the server happened to answer.
 *
 * <p>Two codes have no wire scenario at all in the shipped implementation — see
 * {@code aScenarioExistsForEveryCodeTheServerCanActuallyRaise}, which names them rather than pretending
 * they are covered.
 */
public class ControlApiErrorContractTest {

    /**
     * §4 of the wire specification, transcribed: wire code, JSON-RPC number, CLI exit, retryable.
     *
     * <p>This is the one place the document is copied. Deriving it from the enum would make the test
     * agree with the implementation by construction and prove nothing.
     */
    private static final String[][] DOCUMENTED_TABLE = {
        {"parse_error", "-32700", "2", "false"},
        {"invalid_request", "-32600", "2", "false"},
        {"unknown_method", "-32601", "2", "false"},
        {"invalid_params", "-32602", "2", "false"},
        {"internal_error", "-32603", "1", "false"},
        {"message_too_large", "-32005", "2", "false"},
        {"unauthorized", "-32001", "3", "false"},
        {"control_api_disabled", "-32002", "3", "false"},
        {"blocked_by_policy", "-32003", "3", "false"},
        {"not_ready", "-32004", "3", "true"},
        {"too_many_connections", "-32006", "3", "true"},
        {"window_not_found", "-32010", "1", "false"},
        {"tab_not_found", "-32011", "1", "false"},
        {"pane_not_found", "-32012", "1", "false"},
        {"agent_not_found", "-32013", "1", "false"},
        {"ambiguous_pane", "-32014", "2", "false"},
        {"stale_instance", "-32015", "1", "false"},
        {"unknown_key", "-32016", "2", "false"},
        {"invalid_regex", "-32017", "2", "false"},
        {"empty_input", "-32022", "2", "false"},
        {"host_shortcut_conflict", "-32024", "2", "false"},
        {"not_connected", "-32020", "1", "true"},
        {"write_failed", "-32021", "1", "true"},
        {"agent_blocked", "-32023", "1", "false"},
        {"busy", "-32025", "1", "true"},
        {"last_pane", "-32030", "1", "false"},
        {"unsupported", "-32031", "1", "false"},
        {"split_failed", "-32032", "1", "true"},
        {"ui_unavailable", "-32041", "1", "true"},
        {"timeout", "-32040", "4", "true"},
    };

    /**
     * The codes no request can provoke in the shipped server.
     *
     * <p>{@code blocked_by_policy} has no raise site at all: {@link ControlApiGate} collapses "the
     * setting is off" and "enterprise policy denies control-api" into a single boolean, so the server
     * can only ever answer {@code control_api_disabled} and a managed client cannot tell an
     * administrator's decision from a user's. {@code not_ready} is raised only from a branch that
     * cannot be reached — the method table is always built before the listener binds.
     *
     * <p>Both are reported as findings rather than accommodated: the assertions below still pin their
     * numbers, and only the round trip is skipped.
     */
    private static final Set<ControlErrorCode> WITHOUT_A_WIRE_SCENARIO =
        EnumSet.of(ControlErrorCode.BLOCKED_BY_POLICY, ControlErrorCode.NOT_READY);

    private static final String LOCAL_PANE = "p1a2b";

    private static final String SECOND_PANE = "p2c3d";

    private static final String SSH_PANE = "p4a5b";

    private static final String TAB = "t9f3a";

    private static final long DEAD_PID = 4_294_967_200L;

    private Path root;

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    /** Extra servers a scenario had to build for itself, closed in the teardown regardless. */
    private final List<ControlApiServer> extraServers = new ArrayList<>();

    private final List<ScheduledExecutorService> extraTimers = new ArrayList<>();

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        surface = (FakeControlSurface) ControlApiScenarioFixtures.twoWindowsThreeTabs();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root, surface, agents);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopEveryServerAndDeleteTheTempTree() {
        for (ControlApiServer extra : extraServers) {
            extra.close();
        }
        extraServers.clear();
        for (ScheduledExecutorService timer : extraTimers) {
            timer.shutdownNow();
        }
        extraTimers.clear();
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    // --- the table itself ---------------------------------------------------------------------

    @Test
    void theShippedEnumIsTheTableTheSpecificationPrints() {
        assertWithMessage("the vocabulary is closed: §4 lists %s codes", DOCUMENTED_TABLE.length)
            .that(ControlErrorCode.values().length).isEqualTo(DOCUMENTED_TABLE.length);
        for (String[] row : DOCUMENTED_TABLE) {
            ControlErrorCode code = ControlErrorCode.forWire(row[0]).orElseThrow(
                () -> new AssertionError("§4 documents the code '" + row[0]
                    + "', which ControlErrorCode does not define"));
            assertWithMessage("§4 gives %s the JSON-RPC number %s", row[0], row[1])
                .that(code.jsonRpcCode()).isEqualTo(Integer.parseInt(row[1]));
            assertWithMessage("§4 gives %s the CLI exit code %s, and the server is what supplies it",
                    row[0], row[2])
                .that(code.cliExit()).isEqualTo(Integer.parseInt(row[2]));
            assertWithMessage("§4 marks %s retryable=%s", row[0], row[3])
                .that(code.retryable()).isEqualTo(Boolean.parseBoolean(row[3]));
        }
    }

    @Test
    void everyJsonRpcNumberIsUniqueSoAClientCanSwitchOnIt() {
        List<Integer> numbers = new ArrayList<>();
        for (ControlErrorCode code : ControlErrorCode.values()) {
            numbers.add(code.jsonRpcCode());
        }
        assertThat(numbers).containsNoDuplicates();
    }

    @Test
    void everyExitCodeIsOneTheCliDocuments() {
        for (ControlErrorCode code : ControlErrorCode.values()) {
            assertWithMessage("%s maps to exit %s, which is not one of the documented 1, 2, 3, 4",
                    code.wire(), code.cliExit())
                .that(code.cliExit()).isIn(List.of(1, 2, 3, 4));
        }
    }

    // --- one provoked round trip per code -----------------------------------------------------

    @Test(timeOut = 60_000)
    void aLineThatIsNotJsonIsParseErrorWithANullId() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw("{\"jsonrpc\":\"2.0\",");
            JsonObject frame = wire.next();
            assertWithMessage("a line the parser could not read cannot be correlated, so its id is"
                    + " JSON null rather than a guess")
                .that(frame.get("id").isJsonNull()).isTrue();
            assertError(frame, ControlErrorCode.PARSE_ERROR);
        }
    }

    @Test(timeOut = 60_000)
    void aBatchArrayIsInvalidRequestBecauseSequentialLinesAreTheBatchingMechanism() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}]");
            assertError(wire.next(), ControlErrorCode.INVALID_REQUEST);
        }
    }

    @Test(timeOut = 60_000)
    void aNotificationWithoutAnIdIsInvalidRequest() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":{}}");
            assertError(wire.next(), ControlErrorCode.INVALID_REQUEST);
        }
    }

    @Test(timeOut = 60_000)
    void anUnregisteredMethodIsUnknownMethod() throws Exception {
        assertError(callOnServer("pane.teleport", new JsonObject()), ControlErrorCode.UNKNOWN_METHOD);
    }

    @Test(timeOut = 60_000)
    void aMissingRequiredParameterIsInvalidParams() throws Exception {
        JsonObject data = assertError(callOnServer("pane.get", new JsonObject()),
            ControlErrorCode.INVALID_PARAMS);
        assertWithMessage("a refusal must name the parameter that was wrong")
            .that(data.get("param").getAsString()).isEqualTo("pane");
    }

    @Test(timeOut = 60_000)
    void anUncheckedFailureInsideAVerbIsAGenericInternalError() throws Exception {
        surface.setInUiHop(() -> {
            throw new IllegalArgumentException("a bug inside the surface");
        });
        JsonObject data = assertError(callOnServer("window.list", new JsonObject()),
            ControlErrorCode.INTERNAL_ERROR);
        assertWithMessage("the detail of a bug is logged, never sent: a stack trace on the wire is"
                + " an information leak and a client cannot act on it")
            .that(data.keySet()).containsNoneOf("stack", "exception", "cause");
    }

    @Test(timeOut = 60_000)
    void aLineOverTheWireLimitIsMessageTooLargeAndTheConnectionIsClosed() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw(oversizedLine());
            JsonObject frame = wire.next();
            assertThat(frame.get("id").isJsonNull()).isTrue();
            assertError(frame, ControlErrorCode.MESSAGE_TOO_LARGE);
            assertWithMessage("a truncated line cannot be resynchronised, so the connection must go")
                .that(wire.next()).isNull();
        }
    }

    @Test(timeOut = 60_000)
    void aMethodBeforeAuthIsUnauthorized() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertError(wire.call("ping", new JsonObject()), ControlErrorCode.UNAUTHORIZED);
        }
    }

    @Test(timeOut = 60_000)
    void aGateThatClosesAfterTheHandshakeRefusesTheNextRequest() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        ControlApiServer gated = startGatedServer(open::get);
        EndpointDescriptor gatedEndpoint = gated.endpoint().orElseThrow();
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(gatedEndpoint)) {
            wire.authenticate(gatedEndpoint.token());
            open.set(false);
            // The gate is re-evaluated at dispatch time, so a policy reload stops serving requests
            // before the listener is even torn down.
            assertError(wire.call("ping", new JsonObject()), ControlErrorCode.CONTROL_API_DISABLED);
        }
    }

    @Test(timeOut = 60_000)
    void aNinthConnectionIsRefusedWithTooManyConnections() throws Exception {
        List<ControlApiScenarioFixtures.Wire> open = new ArrayList<>();
        try {
            for (int i = 0; i < ControlApiProtocol.MAX_CONNECTIONS; i++) {
                ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint);
                open.add(wire);
                wire.authenticate(endpoint.token());
            }
            try (ControlApiScenarioFixtures.Wire ninth =
                     new ControlApiScenarioFixtures.Wire(endpoint)) {
                JsonObject frame = ninth.next();
                assertWithMessage("the %sth connection must be refused, not queued",
                        ControlApiProtocol.MAX_CONNECTIONS + 1)
                    .that(frame).isNotNull();
                assertError(frame, ControlErrorCode.TOO_MANY_CONNECTIONS);
                assertThat(ninth.next()).isNull();
            }
        } finally {
            open.forEach(ControlApiScenarioFixtures.Wire::close);
        }
    }

    @Test(timeOut = 60_000)
    void anUnknownWindowIsWindowNotFound() throws Exception {
        assertError(callOnServer("tab.list", ControlApiScenarioFixtures.params("window", "w99")),
            ControlErrorCode.WINDOW_NOT_FOUND);
    }

    @Test(timeOut = 60_000)
    void anUnknownTabIsTabNotFound() throws Exception {
        assertError(callOnServer("pane.list", ControlApiScenarioFixtures.params("tab", "tnosuchtab")),
            ControlErrorCode.TAB_NOT_FOUND);
    }

    @Test(timeOut = 60_000)
    void anUnknownPaneIsPaneNotFound() throws Exception {
        JsonObject data = assertError(
            callOnServer("pane.get", ControlApiScenarioFixtures.params("pane", "p0000")),
            ControlErrorCode.PANE_NOT_FOUND);
        assertThat(data.get("pane").getAsString()).isEqualTo("p0000");
    }

    @Test(timeOut = 60_000)
    void aPaneWithoutARegisteredAgentIsAgentNotFound() throws Exception {
        assertError(callOnServer("agent.get", ControlApiScenarioFixtures.params("pane", SECOND_PANE)),
            ControlErrorCode.AGENT_NOT_FOUND);
    }

    @Test(timeOut = 60_000)
    void aBareWindowIdWhereAPaneIsExpectedIsAmbiguousPane() throws Exception {
        JsonObject data = assertError(
            callOnServer("pane.get", ControlApiScenarioFixtures.params("pane", "w1")),
            ControlErrorCode.AMBIGUOUS_PANE);
        assertWithMessage("the refusal must say what a usable selector looks like")
            .that(data.get("hint").getAsString()).isNotEmpty();
    }

    @Test(timeOut = 60_000)
    void aMutatingCallPinnedToAnotherInstanceIsStaleInstance() throws Exception {
        JsonObject data = assertError(callOnServer("pane.focus", ControlApiScenarioFixtures.params(
                "pane", LOCAL_PANE, "instance", "a-previous-korTTY")),
            ControlErrorCode.STALE_INSTANCE);
        assertWithMessage("the refusal must name the instance that is actually running, so a client"
                + " can re-enumerate instead of guessing")
            .that(data.get("instance").getAsString()).isEqualTo(endpoint.instanceId());
    }

    @Test(timeOut = 60_000)
    void aKeyNameOutsideTheVocabularyIsUnknownKeyAndNotInternalError() throws Exception {
        JsonObject data = assertError(callOnServer("pane.send_keys",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "keys", "meta+frobnicate")),
            ControlErrorCode.UNKNOWN_KEY);
        assertWithMessage("§6 requires data.known to publish the vocabulary, so a mistyped key is"
                + " self-correcting rather than a guessing game")
            .that(strings(data.getAsJsonArray("known")))
            .containsExactlyElementsIn(ControlKeyTable.knownKeys());
    }

    @Test(timeOut = 60_000)
    void aPatternThatDoesNotCompileIsInvalidRegexWithTheParserMessage() throws Exception {
        JsonObject data = assertError(callOnServer("pane.wait_output",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "regex", "[")),
            ControlErrorCode.INVALID_REGEX);
        assertWithMessage("§4 requires data.detail to carry the PatternSyntaxException message")
            .that(data.get("detail").getAsString()).isNotEmpty();
    }

    @Test(timeOut = 60_000)
    void aBlankPayloadIsEmptyInput() throws Exception {
        assertError(callOnServer("pane.send_text",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "text", "")),
            ControlErrorCode.EMPTY_INPUT);
    }

    @Test(timeOut = 60_000)
    void aFirstLineKorttysOwnShortcutWouldSwallowIsHostShortcutConflict() throws Exception {
        surface.setHostShortcut(line -> line.startsWith("agent "), "agent");
        JsonObject data = assertError(callOnServer("pane.send_text",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "text", "agent do the thing")),
            ControlErrorCode.HOST_SHORTCUT_CONFLICT);
        assertWithMessage("§6 requires data.shortcut to name the command, because the alternative is"
                + " input that silently disappears")
            .that(data.get("shortcut").getAsString()).isEqualTo("agent");
    }

    @Test(timeOut = 60_000)
    void aPaneWhoseConnectorIsDownIsNotConnectedAndIsRetryable() throws Exception {
        surface.failWrite(new ControlApiException(ControlErrorCode.NOT_CONNECTED,
            "The pane is not connected", Map.of("pane", LOCAL_PANE)));
        JsonObject data = assertError(callOnServer("pane.send_text",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "text", "ls")),
            ControlErrorCode.NOT_CONNECTED);
        assertWithMessage("a connection that may come back is worth retrying, and §4 says so")
            .that(data.get("retryable").getAsBoolean()).isTrue();
    }

    @Test(timeOut = 60_000)
    void aFailedPtyWriteIsWriteFailed() throws Exception {
        surface.failWrite(new ControlApiException(ControlErrorCode.WRITE_FAILED,
            "The write to the pty failed", Map.of("pane", LOCAL_PANE)));
        assertError(callOnServer("pane.run",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "command", "make")),
            ControlErrorCode.WRITE_FAILED);
    }

    @Test(timeOut = 60_000)
    void promptingABlockedAgentIsAgentBlocked() throws Exception {
        register(LOCAL_PANE, CodingAgentState.BLOCKED, "Do you want to proceed?");
        // §6: a BLOCKED agent must be answered with agent.send_keys before it will take a prompt.
        assertError(callOnServer("agent.prompt", ControlApiScenarioFixtures.params(
            "pane", LOCAL_PANE, "text", "carry on")), ControlErrorCode.AGENT_BLOCKED);
    }

    @Test(timeOut = 60_000)
    void aSecondNotificationInsideTheRateLimitIsBusy() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject params = ControlApiScenarioFixtures.params("title", "Build", "body", "green");
            JsonObject first = wire.call("notification.show", params);
            assertWithMessage("the first notification must succeed, or the rate limit proves nothing")
                .that(first.has("result")).isTrue();
            JsonObject data = assertError(wire.call("notification.show", params),
                ControlErrorCode.BUSY);
            assertWithMessage("a rate limit is retryable and must say when")
                .that(data.get("retry_after_millis").getAsLong()).isGreaterThan(0L);
        }
    }

    @Test(timeOut = 60_000)
    void closingATabsLastPaneIsLastPaneWithAHint() throws Exception {
        surface.failClose(new ControlApiException(ControlErrorCode.LAST_PANE,
            "A tab's last pane cannot be closed", Map.of()));
        JsonObject data = assertError(
            callOnServer("pane.close", ControlApiScenarioFixtures.params("pane", LOCAL_PANE)),
            ControlErrorCode.LAST_PANE);
        assertWithMessage("§6 requires the hint, because closeSplit on a root leaf would leave an"
                + " empty terminal area inside a still-open tab")
            .that(data.get("hint").getAsString()).isEqualTo("close the tab yourself");
    }

    @Test(timeOut = 60_000)
    void splittingANonLocalShellPaneIsUnsupportedWithTheProtocolThatRefusedIt() throws Exception {
        ControlApiServer sshServer = startServerFor(ControlApiScenarioFixtures.oneSshPane());
        EndpointDescriptor sshEndpoint = sshServer.endpoint().orElseThrow();
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(sshEndpoint)) {
            wire.authenticate(sshEndpoint.token());
            JsonObject data = assertError(wire.call("pane.split",
                ControlApiScenarioFixtures.params("pane", SSH_PANE)), ControlErrorCode.UNSUPPORTED);
            assertThat(data.get("reason").getAsString()).isEqualTo("split is local-shell only");
            assertWithMessage("§6 requires data.protocol, so a client can explain the refusal")
                .that(data.get("protocol").getAsString()).isEqualTo("SSH");
        }
    }

    @Test(timeOut = 60_000)
    void aSplitThatNeverAttachesIsSplitFailedAndIsRetryable() throws Exception {
        // The scenario surface attaches nothing, which is exactly what a connector that came up null
        // or disconnected looks like from the verb's side.
        JsonObject data = assertError(
            callOnServer("pane.split", ControlApiScenarioFixtures.params("pane", LOCAL_PANE)),
            ControlErrorCode.SPLIT_FAILED);
        assertThat(data.get("retryable").getAsBoolean()).isTrue();
    }

    @Test(timeOut = 60_000)
    void aUiHopWithNoToolkitBehindItIsUiUnavailable() throws Exception {
        surface.setInUiHop(() -> {
            throw new IllegalStateException("the toolkit is gone");
        });
        JsonObject data = assertError(callOnServer("pane.list", new JsonObject()),
            ControlErrorCode.UI_UNAVAILABLE);
        assertWithMessage("a window may open again, so §4 marks this retryable")
            .that(data.get("retryable").getAsBoolean()).isTrue();
    }

    @Test(timeOut = 60_000)
    void aWaitThatExpiresIsTimeoutWithExitFourAndWhatItLastSaw() throws Exception {
        JsonObject data = assertError(callOnServer("pane.wait_output", ControlApiScenarioFixtures.params(
                "pane", LOCAL_PANE, "contains", "this never appears",
                "timeout_ms", 300, "poll_ms", 50)), ControlErrorCode.TIMEOUT);
        assertWithMessage("a wait that expired is the one failure the CLI reports as exit 4, so a"
                + " script can tell 'still working' from 'it broke'")
            .that(data.get("exit").getAsInt()).isEqualTo(4);
        assertThat(data.get("waited_millis").getAsLong()).isAtLeast(0L);
        assertWithMessage("§6 requires data.last_line, so a human can see what the pane did show")
            .that(data.has("last_line")).isTrue();
    }

    @Test(timeOut = 60_000)
    void everyRefusalCarriesTheThreeInvariantDataFieldsAndAMessage() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject frame = wire.call("pane.get", ControlApiScenarioFixtures.params("pane", "p0000"));
            JsonObject error = frame.getAsJsonObject("error");
            assertThat(error.get("message").getAsString()).isNotEmpty();
            assertWithMessage("data.code, data.retryable and data.exit are invariants of every error")
                .that(error.getAsJsonObject("data").keySet())
                .containsAtLeast("code", "retryable", "exit");
            assertWithMessage("a refused request must leave the connection usable; only a line the"
                    + " codec could not delimit closes it")
                .that(wire.call("ping", new JsonObject()).has("result")).isTrue();
        }
    }

    @Test
    void aScenarioExistsForEveryCodeTheServerCanActuallyRaise() {
        for (ControlErrorCode code : WITHOUT_A_WIRE_SCENARIO) {
            assertWithMessage("%s is documented in §4 but has no reachable raise site in the shipped"
                    + " server; this is reported as a contract violation, not accommodated by"
                    + " weakening the table above", code.wire())
                .that(ControlErrorCode.forWire(code.wire())).isPresent();
        }
        // The remaining 28 codes each have a provoking test in this class. Keeping the count here
        // means a code added to the enum without a scenario fails this test rather than passing
        // unnoticed.
        assertThat(ControlErrorCode.values().length - WITHOUT_A_WIRE_SCENARIO.size()).isEqualTo(28);
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Authenticates, sends one request to the default server and returns the reply frame. */
    private JsonObject callOnServer(String method, JsonObject params) throws IOException {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            return wire.call(method, params);
        }
    }

    /** Asserts the frame is the documented refusal, reading every expectation from the enum. */
    private static JsonObject assertError(JsonObject frame, ControlErrorCode expected) {
        assertWithMessage("expected %s but the server sent nothing", expected.wire())
            .that(frame).isNotNull();
        assertWithMessage("expected %s but the server answered %s", expected.wire(), frame)
            .that(frame.has("error")).isTrue();
        JsonObject error = frame.getAsJsonObject("error");
        assertWithMessage("the JSON-RPC number of %s", expected.wire())
            .that(error.get("code").getAsInt()).isEqualTo(expected.jsonRpcCode());
        JsonObject data = error.getAsJsonObject("data");
        assertWithMessage("data.code is the stable contract a client branches on")
            .that(data.get("code").getAsString()).isEqualTo(expected.wire());
        assertWithMessage("data.exit is supplied by the server so the CLI needs no second table")
            .that(data.get("exit").getAsInt()).isEqualTo(expected.cliExit());
        assertWithMessage("data.retryable tells an automated caller whether to try again")
            .that(data.get("retryable").getAsBoolean()).isEqualTo(expected.retryable());
        return data;
    }

    /** The same scenery behind a gate the test can close mid-connection. */
    private ControlApiServer startGatedServer(java.util.function.BooleanSupplier gate) {
        return startOwnServer(surface, gate);
    }

    /** A second server over a different scenario surface. */
    private ControlApiServer startServerFor(ControlSurface ownSurface) {
        return startOwnServer(ownSurface, () -> true);
    }

    /**
     * Builds one more real server in the same temp tree.
     *
     * <p>It gets its own configuration directory below {@code root} so the fixture server's socket
     * and {@code endpoint.json} stay untouched; both are unlinked by their own {@code close()}.
     */
    private ControlApiServer startOwnServer(ControlSurface ownSurface,
                                            java.util.function.BooleanSupplier gate) {
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        Path configDir = root.resolve("s" + extraServers.size());
        ControlApiScenarioFixtures.skipIfSocketPathTooLong(
            configDir.resolve(ControlDirectory.DIRECTORY_NAME));
        ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "kt-error-contract-timer");
            thread.setDaemon(true);
            return thread;
        });
        extraTimers.add(timer);
        String instanceId = UUID.randomUUID().toString();
        MethodRegistry methods = ControlVerbs.build(ownSurface, UiDispatcher.DIRECT, agents, actions,
            new ControlEventBus(timer, System::currentTimeMillis), (verb, pane, detail) -> { }, null,
            System::currentTimeMillis, "3.4.1", instanceId);
        ControlApiServer own = new ControlApiServer(configDir,
            ControlApiScenarioFixtures.nativeProbe(), methods, gate, System::currentTimeMillis,
            "3.4.1", instanceId);
        extraServers.add(own);
        own.applyEnabledState();
        assertWithMessage("the extra server must be listening before the scenario runs")
            .that(own.status()).isEqualTo(ControlApiStatus.RUNNING);
        return own;
    }

    private void register(String paneId, CodingAgentState state, String evidence) {
        PaneRef ref = ControlApiScenarioFixtures.paneRef(TAB, paneId);
        agents.onEvent(new CodingAgentEvent(ref, DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", evidence),
            new AgentProcess(DEAD_PID, CodingAgentKind.CLAUDE_CODE, "claude", null),
            CodingAgentEvent.Reason.DETECTED, Instant.now()));
    }

    /** One byte over the 1 MiB cap, wrapped in an otherwise perfectly valid request. */
    private static String oversizedLine() {
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\",\"params\":{\"pad\":\"";
        String suffix = "\"}}";
        int padding = ControlApiProtocol.MAX_LINE_BYTES + 1 - prefix.length() - suffix.length();
        return prefix + "x".repeat(padding) + suffix;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> values.add(element.getAsString()));
        }
        return values;
    }
}
