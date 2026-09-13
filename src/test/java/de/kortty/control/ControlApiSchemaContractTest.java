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
                    assertWithMessage("a parameter of %s must carry name, type, required, default and"
                            + " doc, or a client cannot build the call", name)
                        .that(param.getAsJsonObject().keySet())
                        .containsExactly("name", "type", "required", "default", "doc");
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
                // events.overflow is deliberately NOT checked here: the shipped document gives it the
                // same field list as an agent event, while the frame the bus actually emits carries
                // "dropped" and "subscription_id" and none of those ids. That disagreement is
                // reported as a finding rather than pinned as if it were the contract.
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

    private static List<String> errorsOf(JsonObject schema, String method) {
        for (JsonElement element : schema.getAsJsonArray("methods")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("name").getAsString().equals(method)) {
                return strings(entry.getAsJsonArray("errors"));
            }
        }
        throw new AssertionError("api.schema does not describe " + method);
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
