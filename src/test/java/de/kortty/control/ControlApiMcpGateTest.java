package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The MCP gate and the fail-closed allowlist over a real socket (AI-21): a connection that declares
 * {@code client_kind = "mcp"} is served only while policy and the user's switch allow it, only the
 * allowlisted verbs, and the write verbs only with the second switch — while a plain client on the
 * very same server is not affected at all.
 */
public class ControlApiMcpGateTest {

    private static final String LOCAL_PANE = "p1a2b";

    private final AtomicReference<McpGate.Verdict> mcpGate =
        new AtomicReference<>(McpGate.Verdict.READ_ONLY);

    private Path root;

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    @BeforeMethod
    void startTheServer() throws IOException {
        mcpGate.set(McpGate.Verdict.READ_ONLY);
        root = ControlApiScenarioFixtures.newTempRoot();
        surface = (FakeControlSurface) ControlApiScenarioFixtures.twoWindowsThreeTabs();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root, surface, agents, mcpGate::get);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    // --- the gate itself ----------------------------------------------------------------------

    @Test
    void theGateIsReadOnlyUnlessTheWriteSwitchIsOnAndNeverOpensWithoutPolicyAndSetting() {
        assertThat(McpGate.evaluate(true, true, false)).isEqualTo(McpGate.Verdict.READ_ONLY);
        assertThat(McpGate.evaluate(true, true, null)).isEqualTo(McpGate.Verdict.READ_ONLY);
        assertThat(McpGate.evaluate(true, true, true)).isEqualTo(McpGate.Verdict.READ_WRITE);
        assertThat(McpGate.evaluate(false, true, true)).isEqualTo(McpGate.Verdict.BLOCKED_BY_POLICY);
        assertThat(McpGate.evaluate(false, false, false)).isEqualTo(McpGate.Verdict.BLOCKED_BY_POLICY);
        assertWithMessage("the write switch can never open a gate the server switch keeps closed")
            .that(McpGate.evaluate(true, false, true)).isEqualTo(McpGate.Verdict.DISABLED_BY_SETTING);
        assertThat(McpGate.evaluate(null, true, true)).isEqualTo(McpGate.Verdict.NOT_READY);
        assertThat(McpGate.evaluate(true, null, true)).isEqualTo(McpGate.Verdict.NOT_READY);
        for (McpGate.Verdict verdict : McpGate.Verdict.values()) {
            assertWithMessage("%s", verdict).that(verdict.errorCode() == null).isEqualTo(verdict.isOpen());
        }
    }

    @Test
    void bothSwitchesAreOffByDefaultAndThePolicyLegComesFromMcpServerAndControlApi() {
        GlobalSettings fresh = new GlobalSettings();
        assertThat(fresh.isMcpServerEnabled()).isFalse();
        assertThat(fresh.isMcpWriteToolsEnabled()).isFalse();
        assertThat(McpGate.verdict(fresh, EffectivePolicy.unrestricted()))
            .isEqualTo(McpGate.Verdict.DISABLED_BY_SETTING);

        GlobalSettings on = new GlobalSettings();
        on.setMcpServerEnabled(true);
        assertThat(McpGate.verdict(on, EffectivePolicy.unrestricted())).isEqualTo(McpGate.Verdict.READ_ONLY);
        on.setMcpWriteToolsEnabled(true);
        assertThat(McpGate.verdict(on, EffectivePolicy.unrestricted())).isEqualTo(McpGate.Verdict.READ_WRITE);
        assertThat(McpGate.verdict(on, EffectivePolicy.lockdown()))
            .isEqualTo(McpGate.Verdict.BLOCKED_BY_POLICY);
        assertThat(McpGate.verdict(on, null)).isEqualTo(McpGate.Verdict.NOT_READY);
        assertThat(McpGate.verdict(null, EffectivePolicy.unrestricted())).isEqualTo(McpGate.Verdict.NOT_READY);
    }

    // --- refusals at the handshake --------------------------------------------------------------

    @Test(timeOut = 60_000)
    void aPolicyThatDeniesTheMcpServerRefusesTheMcpHandshake() throws Exception {
        mcpGate.set(McpGate.Verdict.BLOCKED_BY_POLICY);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertRefused(authMcp(wire), ControlErrorCode.BLOCKED_BY_POLICY);
            assertWithMessage("a refused MCP handshake must not leave the connection authenticated")
                .that(code(wire.call("ping", new JsonObject()))).isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
        }
    }

    @Test(timeOut = 60_000)
    void theSwitchedOffSettingRefusesTheMcpHandshake() throws Exception {
        mcpGate.set(McpGate.Verdict.DISABLED_BY_SETTING);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertRefused(authMcp(wire), ControlErrorCode.MCP_SERVER_DISABLED);
        }
    }

    @Test(timeOut = 60_000)
    void anUnknownClientKindIsRefusedAtAuth() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject frame = wire.call(ControlConnection.AUTH_METHOD, ControlApiScenarioFixtures.params(
                "token", endpoint.token(), "client", "gate-test", "client_kind", "agent"));
            JsonObject data = assertRefused(frame, ControlErrorCode.INVALID_PARAMS);
            assertThat(data.get("param").getAsString()).isEqualTo("client_kind");
            assertThat(code(wire.call("pane.list", new JsonObject())))
                .isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
        }
    }

    @Test(timeOut = 60_000)
    void aMalformedMcpSessionIdIsRefusedAtAuth() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject frame = wire.call(ControlConnection.AUTH_METHOD, ControlApiScenarioFixtures.params(
                "token", endpoint.token(), "client", "gate-test", "client_kind", "mcp",
                "mcp_session", "short\nid"));
            JsonObject data = assertRefused(frame, ControlErrorCode.INVALID_PARAMS);
            assertThat(data.get("param").getAsString()).isEqualTo(ControlConnection.MCP_SESSION_PARAM);
        }
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertThat(wire.call(ControlConnection.AUTH_METHOD, ControlApiScenarioFixtures.params(
                "token", endpoint.token(), "client", "gate-test", "client_kind", "mcp",
                "mcp_session", "0b6f2f0e-6c1d-4a59-9a8e-3a1c2b4d5e6f")).has("result")).isTrue();
        }
    }

    // --- refusals after the handshake ------------------------------------------------------------

    @Test(timeOut = 60_000)
    void aWriteVerbWithoutTheWriteSwitchIsRefusedAndWorksOnceItIsOn() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertThat(authMcp(wire).has("result")).isTrue();
            JsonObject sendText = ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "text", "ls");
            JsonObject data = assertRefused(wire.call("pane.send_text", sendText),
                ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP);
            assertThat(data.get("reason").getAsString()).isEqualTo(McpMethodAllowlist.REASON_WRITE_TOOLS_OFF);
            for (String write : List.of("pane.run", "pane.send_keys")) {
                assertThat(code(wire.call(write, ControlApiScenarioFixtures.params("pane", LOCAL_PANE))))
                    .isEqualTo(ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP.wire());
            }
            assertWithMessage("nothing may reach the pane while the write switch is off")
                .that(surface.written(LOCAL_PANE)).isEmpty();

            mcpGate.set(McpGate.Verdict.READ_WRITE);
            assertWithMessage("the gate is re-read per request, so the switch applies at once")
                .that(wire.call("pane.send_text", sendText).has("result")).isTrue();
        }
    }

    @Test(timeOut = 60_000)
    void verbsOffTheAllowlistAreRefusedEvenWithWriteToolsOn() throws Exception {
        mcpGate.set(McpGate.Verdict.READ_WRITE);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertThat(authMcp(wire).has("result")).isTrue();
            for (String method : List.of("events.subscribe", "agent.send_text", "agent.prompt",
                    "agent.send_keys", "agent.start", "pane.split", "pane.close", "pane.focus",
                    "tab.focus", "tab.create", "notification.show", "no.such.verb")) {
                JsonObject data = assertRefused(wire.call(method,
                    ControlApiScenarioFixtures.params("pane", LOCAL_PANE)),
                    ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP);
                assertWithMessage("%s", method).that(data.get("method").getAsString()).isEqualTo(method);
                assertWithMessage("%s", method).that(data.get("reason").getAsString())
                    .isEqualTo(McpMethodAllowlist.REASON_NOT_EXPOSED);
            }
            assertThat(surface.written(LOCAL_PANE)).isEmpty();
        }
    }

    @Test(timeOut = 60_000)
    void switchingTheMcpServerOffStopsAnOpenConnectionAtTheNextRequest() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertThat(authMcp(wire).has("result")).isTrue();
            assertThat(wire.call("pane.list", new JsonObject()).has("result")).isTrue();
            mcpGate.set(McpGate.Verdict.DISABLED_BY_SETTING);
            assertThat(code(wire.call("pane.list", new JsonObject())))
                .isEqualTo(ControlErrorCode.MCP_SERVER_DISABLED.wire());
            mcpGate.set(McpGate.Verdict.BLOCKED_BY_POLICY);
            assertThat(code(wire.call("ping", new JsonObject())))
                .isEqualTo(ControlErrorCode.BLOCKED_BY_POLICY.wire());
        }
    }

    // --- what an MCP client is shown ---------------------------------------------------------------

    @Test(timeOut = 60_000)
    void theReadVerbsAnswerAndTheHelloAndSchemaListOnlyWhatTheClientMayCall() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = authMcp(wire).getAsJsonObject("result");
            assertThat(strings(hello.getAsJsonArray("methods"))).containsExactly("auth", "ping",
                "api.schema", "window.list", "tab.list", "pane.list", "pane.current", "pane.get",
                "pane.read", "pane.wait_output", "agent.list", "agent.get");
            assertWithMessage("every advertised capability names a verb an MCP client is refused")
                .that(hello.getAsJsonArray("capabilities")).isEmpty();
            assertThat(hello.get("client_kind").getAsString()).isEqualTo("mcp");
            assertThat(hello.get("mcp_write_tools").getAsBoolean()).isFalse();

            for (String read : List.of("ping", "window.list", "tab.list", "pane.list", "agent.list")) {
                assertWithMessage("%s", read).that(wire.call(read, new JsonObject()).has("result")).isTrue();
            }
            assertThat(wire.call("pane.read",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE)).has("result")).isTrue();

            JsonObject schema = wire.call("api.schema", new JsonObject()).getAsJsonObject("result");
            List<String> described = new ArrayList<>();
            for (JsonElement entry : schema.getAsJsonArray("methods")) {
                described.add(entry.getAsJsonObject().get("name").getAsString());
            }
            assertThat(described).containsExactlyElementsIn(strings(hello.getAsJsonArray("methods")));
            assertThat(schema.getAsJsonArray("reserved")).isEmpty();
            assertThat(code(wire.call("api.schema", ControlApiScenarioFixtures.params("method", "pane.split"))))
                .isEqualTo(ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP.wire());
            assertThat(wire.call("api.schema", ControlApiScenarioFixtures.params("method", "pane.read"))
                .has("result")).isTrue();
        }
        mcpGate.set(McpGate.Verdict.READ_WRITE);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = authMcp(wire).getAsJsonObject("result");
            assertThat(strings(hello.getAsJsonArray("methods")))
                .containsAtLeast("pane.send_text", "pane.run", "pane.send_keys");
            assertThat(hello.get("mcp_write_tools").getAsBoolean()).isTrue();
        }
    }

    @Test(timeOut = 60_000)
    void aPlainClientOnTheSameServerIsNotAffectedEvenWithTheMcpServerOff() throws Exception {
        mcpGate.set(McpGate.Verdict.BLOCKED_BY_POLICY);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            JsonObject hello = wire.call(ControlConnection.AUTH_METHOD, ControlApiScenarioFixtures.params(
                "token", endpoint.token(), "client", "kortty-cli", "client_kind", "cli"))
                .getAsJsonObject("result");
            assertThat(strings(hello.getAsJsonArray("methods"))).containsAtLeast("events.subscribe",
                "pane.split", "agent.prompt");
            assertThat(hello.has("mcp_write_tools")).isFalse();
            assertThat(wire.call("events.subscribe", new JsonObject()).has("result")).isTrue();
            assertThat(wire.call("pane.send_text",
                ControlApiScenarioFixtures.params("pane", LOCAL_PANE, "text", "ls")).has("result")).isTrue();
        }
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            assertWithMessage("a client that sends no client_kind is a cli client")
                .that(wire.authenticate(endpoint.token()).has("result")).isTrue();
            assertThat(code(wire.call("no.such.verb", new JsonObject())))
                .isEqualTo(ControlErrorCode.UNKNOWN_METHOD.wire());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private JsonObject authMcp(ControlApiScenarioFixtures.Wire wire) throws IOException {
        return wire.call(ControlConnection.AUTH_METHOD, ControlApiScenarioFixtures.params(
            "token", endpoint.token(), "client", "gate-test", "client_kind", "mcp"));
    }

    private static String code(JsonObject frame) {
        assertWithMessage("expected an error but got %s", frame).that(frame.has("error")).isTrue();
        return frame.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString();
    }

    private static JsonObject assertRefused(JsonObject frame, ControlErrorCode expected) {
        assertWithMessage("expected %s but got %s", expected.wire(), frame).that(code(frame))
            .isEqualTo(expected.wire());
        assertThat(frame.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(expected.jsonRpcCode());
        return frame.getAsJsonObject("error").getAsJsonObject("data");
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }
}
