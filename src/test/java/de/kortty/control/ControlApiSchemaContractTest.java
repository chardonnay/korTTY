package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@code api.schema} is the discovery surface every non-human client reads before it composes a call,
 * so a drift between it and the dispatcher is a drift a client cannot detect and cannot work around.
 *
 * <p>This suite asks the question over the socket rather than from the registry object: the document
 * a client receives must describe the table that will actually answer it. Every assertion is written
 * against the <em>documented</em> shape — {@code errors[]} entries are checked for membership in
 * {@link ControlErrorCode}, not against a list copied out of the implementation, and
 * {@code api.schema.keys} is compared with {@link ControlKeyTable#knownKeys()}, which is what
 * {@code pane.send_keys} will really accept.
 *
 * <p>The reserved verbs are the other half of the contract: {@code tab.create}, {@code tab.close} and
 * {@code tab.rename} exist in the vocabulary and must answer {@code unsupported} with a reason, never
 * {@code unknown_method}, so a herdr-shaped client gets a definite answer instead of guessing whether
 * it spelled the verb wrong.
 */
public class ControlApiSchemaContractTest {

    /**
     * Every method {@code stufe3-final-wire.md} §6 defines, transcribed once.
     *
     * <p>Transcribed rather than read from the registry on purpose: a verb silently dropped from the
     * table would still satisfy an assertion that compares the table with itself.
     */
    private static final Set<String> DOCUMENTED_METHODS = new LinkedHashSet<>(List.of(
        "ping", "auth", "api.schema",
        "events.subscribe", "events.unsubscribe",
        "window.list", "tab.list", "tab.focus",
        "pane.list", "pane.current", "pane.get", "pane.resolve", "pane.focus", "pane.read",
        "pane.send_text", "pane.run", "pane.send_keys", "pane.wait_output", "pane.split", "pane.close",
        "agent.list", "agent.get", "agent.explain", "agent.prompt", "agent.send_keys", "agent.wait",
        "agent.rename", "agent.start",
        "notification.show"));

    /**
     * §6's parameter list for every method, transcribed: a client composes its call from exactly
     * these names.
     *
     * <p>Transcribed rather than read from {@link MethodSpec}, because a schema compared with the
     * table that produced it agrees with itself by construction and proves nothing.
     */
    private static final Map<String, List<String>> DOCUMENTED_PARAMS = Map.ofEntries(
        Map.entry("ping", List.of()),
        Map.entry("auth", List.of("token", "client")),
        Map.entry("api.schema", List.of("method")),
        Map.entry("events.subscribe", List.of("kinds", "panes", "include_evidence")),
        Map.entry("events.unsubscribe", List.of("subscription_id")),
        Map.entry("window.list", List.of()),
        Map.entry("tab.list", List.of("window")),
        Map.entry("tab.focus", List.of("tab", "instance")),
        Map.entry("pane.list", List.of("window", "tab", "local_shell_only")),
        Map.entry("pane.current", List.of()),
        Map.entry("pane.get", List.of("pane")),
        Map.entry("pane.resolve", List.of("pids")),
        Map.entry("pane.focus", List.of("pane", "raise", "instance")),
        Map.entry("pane.read", List.of("pane", "mode", "lines")),
        Map.entry("pane.send_text",
            List.of("pane", "text", "submit", "bracketed", "allow_shortcut_conflict", "instance")),
        Map.entry("pane.run", List.of("pane", "command", "instance")),
        Map.entry("pane.send_keys", List.of("pane", "keys", "instance")),
        Map.entry("pane.wait_output",
            List.of("pane", "regex", "contains", "mode", "lines", "timeout_ms", "poll_ms")),
        Map.entry("pane.split", List.of("pane", "orientation", "focus", "instance")),
        Map.entry("pane.close", List.of("pane", "instance")),
        Map.entry("agent.list", List.of("state", "kind", "tab", "window")),
        Map.entry("agent.get", List.of("pane")),
        Map.entry("agent.explain", List.of("pane")),
        Map.entry("agent.prompt", List.of("pane", "text", "wait_until", "timeout_ms", "instance")),
        Map.entry("agent.send_keys", List.of("pane", "keys", "instance")),
        Map.entry("agent.wait", List.of("pane", "until", "timeout_ms")),
        Map.entry("agent.rename", List.of("pane", "alias", "instance")),
        Map.entry("agent.start", List.of("pane", "split_from", "orientation", "kind", "command",
            "prompt", "ready_timeout_ms", "wait", "until", "timeout_ms", "instance")),
        Map.entry("notification.show", List.of("title", "body")));

    /** The parameters §6 marks required: a call without one of these is refused. */
    private static final Map<String, List<String>> REQUIRED_PARAMS = Map.ofEntries(
        Map.entry("auth", List.of("token")),
        Map.entry("tab.focus", List.of("tab")),
        Map.entry("pane.get", List.of("pane")),
        Map.entry("pane.resolve", List.of("pids")),
        Map.entry("pane.focus", List.of("pane")),
        Map.entry("pane.read", List.of("pane")),
        Map.entry("pane.send_text", List.of("pane", "text")),
        Map.entry("pane.run", List.of("pane", "command")),
        Map.entry("pane.send_keys", List.of("pane", "keys")),
        Map.entry("pane.wait_output", List.of("pane")),
        Map.entry("pane.split", List.of("pane")),
        Map.entry("pane.close", List.of("pane")),
        Map.entry("agent.get", List.of("pane")),
        Map.entry("agent.explain", List.of("pane")),
        Map.entry("agent.prompt", List.of("pane", "text")),
        Map.entry("agent.send_keys", List.of("pane", "keys")),
        Map.entry("agent.wait", List.of("pane", "until")),
        Map.entry("agent.rename", List.of("pane")),
        Map.entry("agent.start", List.of("kind")),
        Map.entry("notification.show", List.of("title", "body")));

    /**
     * What each method's {@code result} must name, transcribed from §8.
     *
     * <p>A result line is the only thing that tells a client what it will get back, so an entry that
     * merely exists is not enough: it has to name the record, or the members, the verb really
     * returns.
     */
    private static final Map<String, List<String>> RESULT_MUST_NAME = Map.ofEntries(
        Map.entry("ping",
            List.of("pong", "instance", "protocol_version", "app_version", "uptime_millis")),
        Map.entry("auth", List.of("api", "protocol_version", "app_version", "pid", "transport",
            "instance_id", "server_time_millis", "ids_survive_restart", "capabilities", "methods")),
        Map.entry("api.schema", List.of("schema")),
        Map.entry("events.subscribe", List.of("subscription_id", "kinds", "queue_depth")),
        Map.entry("events.unsubscribe", List.of("subscribed")),
        Map.entry("window.list", List.of("instance", "windows", "WindowInfo")),
        Map.entry("tab.list", List.of("instance", "tabs", "TabInfo")),
        Map.entry("tab.focus", List.of("ok", "tab", "TabInfo")),
        Map.entry("pane.list", List.of("instance", "panes", "PaneInfo")),
        Map.entry("pane.current", List.of("pane", "PaneInfo")),
        Map.entry("pane.get", List.of("pane", "PaneInfo")),
        Map.entry("pane.resolve", List.of("pane", "PaneInfo", "matched_pid")),
        Map.entry("pane.focus", List.of("ok", "pane", "PaneInfo")),
        Map.entry("pane.read", List.of("PaneText")),
        Map.entry("pane.send_text", List.of("WriteResult")),
        Map.entry("pane.run", List.of("WriteResult")),
        Map.entry("pane.send_keys", List.of("WriteResult")),
        Map.entry("pane.wait_output", List.of("MatchResult")),
        Map.entry("pane.split", List.of("pane", "PaneInfo")),
        Map.entry("pane.close", List.of("ok")),
        Map.entry("agent.list", List.of("agents", "AgentInfo", "totals", "AgentTotals")),
        Map.entry("agent.get", List.of("agent", "AgentInfo")),
        Map.entry("agent.explain", List.of("explain")),
        Map.entry("agent.prompt", List.of("WriteResult")),
        Map.entry("agent.send_keys", List.of("WriteResult")),
        Map.entry("agent.wait", List.of("AgentWaitResult")),
        Map.entry("agent.rename", List.of("agent", "AgentInfo")),
        Map.entry("agent.start", List.of("pane", "PaneInfo", "agent", "AgentInfo", "command")),
        Map.entry("notification.show", List.of("shown", "supported")));

    /**
     * §6's error list for every method except {@code pane.wait_output}, transcribed.
     *
     * <p>This is what a generated client builds its refusal handling from: an error it does not know
     * about is an unhandled branch, and an error it handles but can never receive is dead code it
     * cannot tell from live code. {@code pane.wait_output} is pinned separately, because the shipped
     * schema and §6 disagree about it.
     */
    private static final Map<String, List<String>> DOCUMENTED_ERRORS = Map.ofEntries(
        Map.entry("ping", List.of()),
        Map.entry("auth", List.of("unauthorized", "invalid_params")),
        Map.entry("api.schema", List.of("unknown_method", "invalid_params")),
        Map.entry("events.subscribe", List.of("invalid_params")),
        Map.entry("events.unsubscribe", List.of("invalid_params")),
        Map.entry("window.list", List.of()),
        Map.entry("tab.list", List.of("window_not_found")),
        Map.entry("tab.focus", List.of("tab_not_found", "stale_instance")),
        Map.entry("pane.list", List.of("window_not_found", "tab_not_found")),
        Map.entry("pane.current", List.of()),
        Map.entry("pane.get", List.of("pane_not_found", "ambiguous_pane")),
        Map.entry("pane.resolve", List.of("invalid_params")),
        Map.entry("pane.focus", List.of("pane_not_found", "ambiguous_pane", "stale_instance")),
        Map.entry("pane.read", List.of("pane_not_found", "ambiguous_pane", "invalid_params")),
        Map.entry("pane.send_text", List.of("pane_not_found", "not_connected", "write_failed",
            "empty_input", "host_shortcut_conflict", "stale_instance")),
        Map.entry("pane.run", List.of("pane_not_found", "not_connected", "write_failed",
            "empty_input", "invalid_params", "stale_instance")),
        Map.entry("pane.send_keys", List.of("pane_not_found", "not_connected", "write_failed",
            "empty_input", "unknown_key", "stale_instance")),
        Map.entry("pane.split", List.of("pane_not_found", "not_connected", "unsupported",
            "split_failed", "stale_instance")),
        Map.entry("pane.close", List.of("pane_not_found", "last_pane", "stale_instance")),
        Map.entry("agent.list", List.of("tab_not_found", "window_not_found")),
        Map.entry("agent.get", List.of("pane_not_found", "agent_not_found")),
        Map.entry("agent.explain", List.of("pane_not_found", "agent_not_found")),
        Map.entry("agent.prompt", List.of("pane_not_found", "agent_not_found", "agent_blocked",
            "empty_input", "not_connected", "write_failed", "host_shortcut_conflict", "timeout",
            "stale_instance")),
        Map.entry("agent.send_keys", List.of("pane_not_found", "agent_not_found", "unknown_key",
            "empty_input", "not_connected", "write_failed", "stale_instance")),
        Map.entry("agent.wait",
            List.of("pane_not_found", "agent_not_found", "invalid_params", "timeout")),
        Map.entry("agent.rename", List.of("pane_not_found", "agent_not_found", "stale_instance")),
        Map.entry("agent.start", List.of("pane_not_found", "not_connected", "unsupported",
            "split_failed", "invalid_params", "agent_blocked", "timeout", "stale_instance")),
        Map.entry("notification.show", List.of("invalid_params", "busy", "unsupported")));

    /** §6's error list for {@code pane.wait_output}, the one verb whose schema entry disagrees. */
    private static final List<String> WAIT_OUTPUT_ERRORS =
        List.of("pane_not_found", "invalid_regex", "invalid_params", "timeout");

    /** The three verbs §6 reserves, with the reason it prints. */
    private static final List<String> RESERVED_METHODS = List.of("tab.create", "tab.close", "tab.rename");

    /** The reason string §6 prints for every reserved verb. */
    private static final String RESERVED_REASON = "not_implemented_in_this_version";

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root,
            ControlApiScenarioFixtures.twoWindowsThreeTabs(), agents);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServerAndDeleteTheTempTree() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void theSchemaListsExactlyTheMethodsTheSpecificationDefinesAndTheHandshakeAdvertises()
            throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = result(wire.authenticate(endpoint.token()));
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));

            List<String> schemaNames = names(schema.getAsJsonArray("methods"));
            assertWithMessage("api.schema must describe exactly the verbs the specification defines")
                .that(schemaNames).containsExactlyElementsIn(DOCUMENTED_METHODS);
            assertWithMessage("the hello's method list and api.schema are read by the same client on"
                    + " the same connection; they must not disagree")
                .that(strings(hello.getAsJsonArray("methods")))
                .containsExactlyElementsIn(schemaNames);
            assertWithMessage("registration order is the schema order, so a method appears once")
                .that(schemaNames).containsNoDuplicates();
        }
    }

    @Test(timeOut = 60_000)
    void everyErrorAMethodDeclaresIsAMemberOfTheClosedErrorVocabulary() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            for (JsonElement element : schema.getAsJsonArray("methods")) {
                JsonObject method = element.getAsJsonObject();
                String name = method.get("name").getAsString();
                for (String code : strings(method.getAsJsonArray("errors"))) {
                    assertWithMessage("%s declares the error '%s', which is not in ControlErrorCode;"
                            + " a client branching on data.code would never see it", name, code)
                        .that(ControlErrorCode.forWire(code).isPresent()).isTrue();
                }
            }
        }
    }

    @Test(timeOut = 60_000)
    void everyMethodEntryCarriesTheFieldsTheSchemaDocumentPromises() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            for (JsonElement element : schema.getAsJsonArray("methods")) {
                JsonObject method = element.getAsJsonObject();
                String name = method.get("name").getAsString();
                assertWithMessage("%s must carry every member §8 prints", name)
                    .that(method.keySet()).containsAtLeast("name", "summary", "mutates", "blocking",
                        "cli", "params", "result", "errors", "example_request", "example_response");
                assertWithMessage("%s must document what it returns", name)
                    .that(method.get("result").getAsString()).isNotEmpty();
                for (JsonElement param : method.getAsJsonArray("params")) {
                    JsonObject entry = param.getAsJsonObject();
                    assertWithMessage("a parameter of %s must carry name, type, required, default and"
                            + " doc, or a client cannot build the call", name)
                        .that(entry.keySet())
                        .containsExactly("name", "type", "required", "default", "doc");
                    assertWithMessage("a parameter of %s must be named", name)
                        .that(entry.get("name").getAsString()).isNotEmpty();
                    assertWithMessage("%s.%s must publish a type, or a client cannot tell a string"
                            + " from an int array", name, entry.get("name").getAsString())
                        .that(entry.get("type").getAsString()).isNotEmpty();
                    assertWithMessage("%s.%s must publish the sentence an agent reads before it"
                            + " guesses", name, entry.get("name").getAsString())
                        .that(entry.get("doc").getAsString()).isNotEmpty();
                }
            }
        }
    }

    @Test(timeOut = 60_000)
    void theDocumentedErrorSetOfAWriteVerbIsPublishedInFull() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));

            assertWithMessage("§6 pane.send_text names these five refusals")
                .that(errorsOf(schema, "pane.send_text"))
                .containsAtLeast("pane_not_found", "not_connected", "write_failed", "empty_input",
                    "host_shortcut_conflict");
            assertWithMessage("§6 pane.send_keys must publish unknown_key, because api.schema.keys is"
                    + " what stops an agent guessing a key name")
                .that(errorsOf(schema, "pane.send_keys")).contains("unknown_key");
            assertWithMessage("§6 pane.split names these four")
                .that(errorsOf(schema, "pane.split"))
                .containsAtLeast("pane_not_found", "not_connected", "unsupported", "split_failed");
            assertWithMessage("§6 pane.close refuses a tab's last pane")
                .that(errorsOf(schema, "pane.close")).containsAtLeast("pane_not_found", "last_pane");
            assertWithMessage("§6 pane.wait_output names these four")
                .that(errorsOf(schema, "pane.wait_output"))
                .containsAtLeast("pane_not_found", "invalid_regex", "invalid_params", "timeout");
            assertWithMessage("§6 agent.wait names these four")
                .that(errorsOf(schema, "agent.wait"))
                .containsAtLeast("pane_not_found", "agent_not_found", "invalid_params", "timeout");
        }
    }

    @Test(timeOut = 60_000)
    void theReservedVerbsAnswerUnsupportedWithAReasonRatherThanUnknownMethod() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            assertWithMessage("the schema must publish the reserved verbs, so a client learns they"
                    + " exist and are out of scope rather than guessing at a typo")
                .that(names(schema.getAsJsonArray("reserved")))
                .containsExactlyElementsIn(RESERVED_METHODS);
            for (JsonElement element : schema.getAsJsonArray("reserved")) {
                assertThat(element.getAsJsonObject().get("reason").getAsString())
                    .isEqualTo(RESERVED_REASON);
            }

            for (String method : RESERVED_METHODS) {
                JsonObject data = error(wire.call(method, new JsonObject()));
                assertWithMessage("%s is reserved, so it must answer unsupported, not unknown_method",
                        method)
                    .that(data.get("code").getAsString()).isEqualTo(ControlErrorCode.UNSUPPORTED.wire());
                assertWithMessage("%s must say why it is unsupported", method)
                    .that(data.get("reason").getAsString()).isEqualTo(RESERVED_REASON);
                assertThat(data.get("exit").getAsInt()).isEqualTo(ControlErrorCode.UNSUPPORTED.cliExit());
            }
        }
    }

    @Test(timeOut = 60_000)
    void theKeyVocabularyThePaneVerbsAcceptIsThePublishedOne() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            assertWithMessage("api.schema.keys is the whole point of publishing a vocabulary: it must"
                    + " be exactly what ControlKeyTable will accept")
                .that(strings(schema.getAsJsonArray("keys")))
                .containsExactlyElementsIn(ControlKeyTable.knownKeys()).inOrder();
            assertWithMessage("the named keys of §6 must all be in the published vocabulary")
                .that(strings(schema.getAsJsonArray("keys")))
                .containsAtLeast("enter", "esc", "tab", "shift+tab", "backspace", "space", "up",
                    "down", "left", "right", "home", "end", "pageup", "pagedown", "delete", "insert",
                    "f1", "f12", "ctrl+a", "ctrl+z", "y", "n");
        }
    }

    @Test(timeOut = 60_000)
    void theErrorCatalogueCoversTheWholeEnumWithItsOwnNumbersExitsAndRetryFlags() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            List<String> published = new ArrayList<>();
            for (JsonElement element : schema.getAsJsonArray("errors")) {
                JsonObject entry = element.getAsJsonObject();
                String wire0 = entry.get("code").getAsString();
                published.add(wire0);
                ControlErrorCode code = ControlErrorCode.forWire(wire0).orElseThrow();
                assertWithMessage("%s must publish the JSON-RPC number it actually sends", wire0)
                    .that(entry.get("json_rpc").getAsInt()).isEqualTo(code.jsonRpcCode());
                assertWithMessage("%s must publish the exit code the CLI will return, because the"
                        + " server supplying it is what keeps the two tables from disagreeing", wire0)
                    .that(entry.get("exit").getAsInt()).isEqualTo(code.cliExit());
                assertThat(entry.get("retryable").getAsBoolean()).isEqualTo(code.retryable());
                assertThat(entry.get("doc").getAsString()).isNotEmpty();
            }
            List<String> everyCode = new ArrayList<>();
            for (ControlErrorCode code : ControlErrorCode.values()) {
                everyCode.add(code.wire());
            }
            assertWithMessage("the vocabulary is closed; a code that is raised but not published"
                    + " leaves a client with nothing to branch on")
                .that(published).containsExactlyElementsIn(everyCode);
        }
    }

    @Test(timeOut = 60_000)
    void theLimitsAndNotesAreTheOnesTheSpecificationPrints() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            JsonObject limits = schema.getAsJsonObject("limits");
            // The numbers of §8, transcribed; comparing them with ControlApiProtocol would only prove
            // the document echoes a constant, not that the constant is the documented one.
            assertThat(limits.get("max_line_bytes").getAsLong()).isEqualTo(1_048_576L);
            assertThat(limits.get("max_result_bytes").getAsLong()).isEqualTo(524_288L);
            assertThat(limits.get("max_connections").getAsInt()).isEqualTo(8);
            assertThat(limits.get("max_wait_ms").getAsLong()).isEqualTo(600_000L);
            assertThat(limits.get("event_queue_depth").getAsInt()).isEqualTo(256);
            assertThat(limits.get("max_read_lines").getAsInt()).isEqualTo(10_000);

            assertWithMessage("§8 requires the sequential-execution note, because it is the only"
                    + " place a client learns that a long wait needs a second connection")
                .that(strings(schema.getAsJsonArray("notes")))
                .containsAtLeast("requests on one connection are executed sequentially",
                    "open a second connection for a long wait plus concurrent calls",
                    "ids are session-scoped; re-enumerate when instance_id changes");

            JsonObject formats = schema.getAsJsonObject("id_formats");
            assertThat(formats.get("window").getAsString()).isEqualTo("w<n>");
            assertThat(formats.get("tab").getAsString()).isEqualTo("t<uuid>");
            assertThat(formats.get("pane").getAsString()).isEqualTo("p<hex>");
            assertThat(formats.get("qualified").getAsString()).isEqualTo("<w>:<t>:<p>");
            assertThat(strings(formats.getAsJsonArray("aliases"))).containsExactly("@focused");
        }
    }

    @Test(timeOut = 60_000)
    void theEventCatalogueNamesEveryKindASubscriptionCanDeliver() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            List<String> kinds = new ArrayList<>();
            for (JsonElement element : schema.getAsJsonArray("events")) {
                JsonObject entry = element.getAsJsonObject();
                String kind = entry.get("kind").getAsString();
                kinds.add(kind);
                assertThat(entry.get("doc").getAsString()).isNotEmpty();
                if (kind.startsWith("agent.")) {
                    assertWithMessage("§6's event frame gives %s these members, and a client builds"
                            + " its reader from this list", kind)
                        .that(strings(entry.getAsJsonArray("fields")))
                        .containsAtLeast("pane_id", "tab_id", "window_id", "agent", "previous_state");
                }
                // events.overflow is not checked here because it carries no ids at all: every
                // kind's field list is compared with the frame that kind really emits in
                // ControlEventBusTest, which is the assertion that can tell them apart.
            }
            assertWithMessage("the published catalogue must be exactly the kinds events.subscribe"
                    + " accepts, or a client can ask for a kind it will never receive")
                .that(kinds).containsExactlyElementsIn(ControlEvent.KINDS);
        }
    }

    @Test(timeOut = 60_000)
    void theSchemaOfOneMethodIsThatMethodsEntryAndAnUnknownNameIsRefused() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject entry = result(wire.call("api.schema",
                ControlApiScenarioFixtures.params("method", "pane.send_text")));
            assertThat(entry.get("name").getAsString()).isEqualTo("pane.send_text");
            assertWithMessage("one method entry is a method entry, not the whole document")
                .that(entry.has("methods")).isFalse();

            JsonObject data = error(wire.call("api.schema",
                ControlApiScenarioFixtures.params("method", "pane.teleport")));
            assertWithMessage("§6 gives api.schema unknown_method for a name that is not a verb")
                .that(data.get("code").getAsString()).isEqualTo(ControlErrorCode.UNKNOWN_METHOD.wire());
        }
    }

    @Test(timeOut = 60_000)
    void aMethodThatIsNeitherRegisteredNorReservedIsUnknownMethod() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject data = error(wire.call("pane.teleport", new JsonObject()));
            assertThat(data.get("code").getAsString()).isEqualTo(ControlErrorCode.UNKNOWN_METHOD.wire());
            assertWithMessage("the refusal must list what does exist, so a typo is self-correcting")
                .that(strings(data.getAsJsonArray("known")))
                .containsExactlyElementsIn(DOCUMENTED_METHODS);
        }
    }

    @Test(timeOut = 60_000)
    void everyMethodPublishesTheParametersAClientComposesItsCallFrom() throws Exception {
        assertWithMessage("the transcribed table must cover every documented method, or a verb could"
                + " slip past this test unexamined")
            .that(DOCUMENTED_PARAMS.keySet()).containsExactlyElementsIn(DOCUMENTED_METHODS);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            for (JsonElement element : schema.getAsJsonArray("methods")) {
                JsonObject method = element.getAsJsonObject();
                String name = method.get("name").getAsString();
                assertWithMessage("§6 gives %s exactly these parameters; api.schema is the only place"
                        + " a non-human client can learn them, so an entry that publishes fewer"
                        + " leaves it unable to build the call at all", name)
                    .that(paramNames(method))
                    .containsExactlyElementsIn(DOCUMENTED_PARAMS.get(name));
                assertWithMessage("%s must mark exactly the parameters §6 requires, or a client"
                        + " omits one and is refused, or supplies one that is ignored", name)
                    .that(requiredParamNames(method))
                    .containsExactlyElementsIn(REQUIRED_PARAMS.getOrDefault(name, List.of()));
            }
        }
    }

    @Test(timeOut = 60_000)
    void everyMethodNamesTheResultItReturnsAndNotJustSomeText() throws Exception {
        assertWithMessage("the transcribed table must cover every documented method")
            .that(RESULT_MUST_NAME.keySet()).containsExactlyElementsIn(DOCUMENTED_METHODS);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            for (JsonElement element : schema.getAsJsonArray("methods")) {
                JsonObject method = element.getAsJsonObject();
                String name = method.get("name").getAsString();
                String published = method.get("result").getAsString();
                for (String fragment : RESULT_MUST_NAME.get(name)) {
                    assertWithMessage("§8 says %s returns %s, and the published result line '%s' does"
                            + " not name it; a client reading the schema cannot tell what it will"
                            + " get back", name, fragment, published)
                        .that(published).contains(fragment);
                }
            }
        }
    }

    @Test(timeOut = 60_000)
    void theErrorSetOfEveryMethodIsTheOneTheSpecificationNames() throws Exception {
        assertWithMessage("every documented method except pane.wait_output, which is pinned on its"
                + " own below, must appear in the transcribed error table")
            .that(DOCUMENTED_ERRORS.keySet())
            .containsExactlyElementsIn(withoutWaitOutput(DOCUMENTED_METHODS));
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            for (String method : DOCUMENTED_ERRORS.keySet()) {
                assertWithMessage("§6 names exactly these refusals for %s. One missing is an"
                        + " unhandled branch in every generated client; one too many is a branch it"
                        + " will never reach and cannot tell from live code", method)
                    .that(errorsOf(schema, method))
                    .containsExactlyElementsIn(DOCUMENTED_ERRORS.get(method));
            }
        }
    }

    /**
     * Pins §6's sentence that {@code pane.wait_output} answers {@code pane_not_found},
     * {@code invalid_regex}, {@code invalid_params} and {@code timeout}.
     *
     * <p>It used to fail against the shipped server, which also declared {@code busy}:
     * {@code PaneIoVerbs} has no {@code BUSY} raise site at all — the only one in the whole of
     * {@code src/main} is {@code NotificationVerbs}' rate limiter — and a wait cannot collide with
     * another wait on the same connection either, because §2 executes one request at a time. The
     * declaration has since been dropped rather than a per-connection wait guard invented for it, so
     * no generated client carries a refusal branch it can never reach.
     */
    @Test(timeOut = 60_000)
    void paneWaitOutputDeclaresOnlyTheRefusalsARequestCanActuallyProvoke() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject schema = result(wire.call("api.schema", new JsonObject()));
            assertWithMessage("§6 pane.wait_output names these four and no others")
                .that(errorsOf(schema, "pane.wait_output"))
                .containsExactlyElementsIn(WAIT_OUTPUT_ERRORS);
            assertWithMessage("§4's busy entry must describe the rate limit it actually means; the"
                    + " second half of the old sentence — 'a wait is already running on this"
                    + " connection' — is unreachable by construction, because requests on one"
                    + " connection are executed one at a time")
                .that(errorDoc(schema, "busy").toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("wait");
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static JsonObject result(JsonObject frame) {
        assertThat(frame).isNotNull();
        if (frame.has("error")) {
            throw new AssertionError("the request failed on the wire: " + frame.get("error"));
        }
        return frame.getAsJsonObject("result");
    }

    private static JsonObject error(JsonObject frame) {
        assertThat(frame).isNotNull();
        assertWithMessage("expected an error object but the server answered %s", frame)
            .that(frame.has("error")).isTrue();
        return frame.getAsJsonObject("error").getAsJsonObject("data");
    }

    /** The prose the schema publishes for one error code. */
    private static String errorDoc(JsonObject schema, String code) {
        for (JsonElement element : schema.getAsJsonArray("errors")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("code").getAsString().equals(code)) {
                return entry.get("doc").getAsString();
            }
        }
        throw new AssertionError("api.schema does not document the error " + code);
    }

    private static List<String> errorsOf(JsonObject schema, String method) {
        for (JsonElement element : schema.getAsJsonArray("methods")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("name").getAsString().equals(method)) {
                return strings(entry.getAsJsonArray("errors"));
            }
        }
        throw new AssertionError("api.schema does not describe " + method);
    }

    private static List<String> paramNames(JsonObject method) {
        List<String> values = new ArrayList<>();
        method.getAsJsonArray("params")
            .forEach(element -> values.add(element.getAsJsonObject().get("name").getAsString()));
        return values;
    }

    private static List<String> requiredParamNames(JsonObject method) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : method.getAsJsonArray("params")) {
            JsonObject param = element.getAsJsonObject();
            if (param.get("required").getAsBoolean()) {
                values.add(param.get("name").getAsString());
            }
        }
        return values;
    }

    /** The documented methods minus the one verb whose error list is pinned on its own. */
    private static List<String> withoutWaitOutput(Set<String> methods) {
        List<String> values = new ArrayList<>(methods);
        values.remove("pane.wait_output");
        return values;
    }

    private static List<String> names(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsJsonObject().get("name").getAsString()));
        return values;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> values.add(element.getAsString()));
        }
        return values;
    }
}
