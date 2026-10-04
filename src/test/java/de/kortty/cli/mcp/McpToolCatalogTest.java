package de.kortty.cli.mcp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.cli.ControlClient;
import de.kortty.cli.ControlDiscovery;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.control.ControlApiScenarioFixtures;
import de.kortty.control.ControlApiServer;
import de.kortty.control.ControlJson;
import de.kortty.control.McpGate;
import de.kortty.control.McpMethodAllowlist;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The hand-written tool catalog against the real control API: every tool names a registered method,
 * every argument is one of that method's wire parameters, the method's required parameters are the
 * tool's required arguments, and the read/write split agrees with the server's MCP allowlist. The
 * same live server then carries {@code kortty-cli mcp}'s production backend end to end.
 */
class McpToolCatalogTest {

    /** Parameters the MCP surface leaves out on purpose. */
    private static final Set<String> DELIBERATELY_OMITTED = Set.of("instance", "allow_shortcut_conflict");

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private final AtomicReference<McpGate.Verdict> gate =
        new AtomicReference<>(McpGate.Verdict.READ_ONLY);

    @BeforeMethod
    void startTheServer() throws IOException {
        // TestNG shares one instance across the methods, so the gate is reset for every one.
        gate.set(McpGate.Verdict.READ_ONLY);
        root = Files.createTempDirectory("kt");
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root,
            ControlApiScenarioFixtures.twoWindowsThreeTabs(), agents, gate::get);
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
        deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void everyToolMapsToARegisteredMethodAndItsParameterNames() throws Exception {
        Map<String, JsonObject> specs = schemaByMethod();
        assertThat(McpToolCatalog.all()).isNotEmpty();
        Set<String> names = new HashSet<>();
        for (McpToolCatalog.McpTool tool : McpToolCatalog.all()) {
            assertWithMessage("duplicate tool name %s", tool.name()).that(names.add(tool.name())).isTrue();
            assertWithMessage("MCP tool names are [a-z_]").that(tool.name()).matches("[a-z_]+");
            JsonObject spec = specs.get(tool.method());
            assertWithMessage("%s calls %s, which korTTY does not register", tool.name(), tool.method())
                .that(spec).isNotNull();
            Set<String> wire = new HashSet<>();
            Set<String> wireRequired = new HashSet<>();
            for (JsonElement param : spec.getAsJsonArray("params")) {
                String name = param.getAsJsonObject().get("name").getAsString();
                wire.add(name);
                if (param.getAsJsonObject().get("required").getAsBoolean()) {
                    wireRequired.add(name);
                }
            }
            assertWithMessage("arguments of %s must be wire parameters of %s", tool.name(), tool.method())
                .that(wire).containsAtLeastElementsIn(tool.parameterNames());
            Set<String> offered = new HashSet<>(wire);
            offered.removeAll(DELIBERATELY_OMITTED);
            assertWithMessage("%s should offer every parameter of %s it does not omit on purpose",
                    tool.name(), tool.method())
                .that(tool.parameterNames()).containsExactlyElementsIn(offered);
            assertWithMessage("required arguments of %s", tool.name())
                .that(tool.requiredNames()).containsExactlyElementsIn(wireRequired);
            assertWithMessage("%s is a write tool exactly when %s mutates", tool.name(), tool.method())
                .that(tool.write()).isEqualTo(spec.get("mutates").getAsBoolean());
            McpMethodAllowlist.Access access = McpMethodAllowlist.classify(tool.method());
            assertWithMessage("the server's allowlist class of %s", tool.method())
                .that(access).isEqualTo(tool.write()
                    ? McpMethodAllowlist.Access.WRITE : McpMethodAllowlist.Access.READ);
        }
    }

    @Test
    void theCatalogIsTheDesignedSetAndEveryAllowlistedVerbWithAToolIsCovered() {
        List<String> names = new ArrayList<>();
        McpToolCatalog.all().forEach(tool -> names.add(tool.name()));
        assertThat(names).containsExactly("pane_list", "pane_read", "pane_wait_output", "tab_list",
            "agent_list", "pane_send_text", "pane_run", "pane_send_keys").inOrder();
        assertThat(McpToolCatalog.listed(false)).hasSize(5);
        assertThat(McpToolCatalog.listed(true)).hasSize(8);
        assertThat(McpToolCatalog.find("agent_prompt")).isNull();
    }

    @Test(timeOut = 60_000)
    void theProductionBackendReadsThroughKorttyAndHidesTheWriteToolsWhileTheyAreOff() throws Exception {
        List<JsonObject> responses = serveMcp(
            init(),
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            call(3, "pane_read", "{\"pane\":\"p1a2b\"}"),
            call(4, "pane_run", "{\"pane\":\"p1a2b\",\"command\":\"ls\"}"));

        List<String> listed = new ArrayList<>();
        responses.get(1).getAsJsonObject("result").getAsJsonArray("tools")
            .forEach(tool -> listed.add(tool.getAsJsonObject().get("name").getAsString()));
        assertThat(listed).doesNotContain("pane_run");

        JsonObject read = responses.get(2).getAsJsonObject("result");
        assertWithMessage("pane_read: %s", read).that(read.get("isError").getAsBoolean()).isFalse();
        JsonObject text = ControlJson.parseObjectStrict(textOf(read));
        assertWithMessage("an MCP read carries the masking counter").that(text.has("masked_count")).isTrue();
        assertThat(textOf(read)).contains("hello");

        JsonObject run = responses.get(3).getAsJsonObject("result");
        assertThat(run.get("isError").getAsBoolean()).isTrue();
        assertThat(textOf(run)).contains("method_not_allowed_for_mcp");
    }

    @Test(timeOut = 60_000)
    void theWriteToolsAreListedOnceKorttyAllowsThem() throws Exception {
        gate.set(McpGate.Verdict.READ_WRITE);
        List<JsonObject> responses = serveMcp(init(),
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        List<String> listed = new ArrayList<>();
        responses.get(1).getAsJsonObject("result").getAsJsonArray("tools")
            .forEach(tool -> listed.add(tool.getAsJsonObject().get("name").getAsString()));
        assertThat(listed).containsAtLeast("pane_send_text", "pane_run", "pane_send_keys");
    }

    @Test(timeOut = 60_000)
    void aSwitchedOffMcpServerIsAToolErrorWithItsCode() throws Exception {
        gate.set(McpGate.Verdict.DISABLED_BY_SETTING);
        List<JsonObject> responses = serveMcp(init(), call(2, "pane_list", "{}"));

        JsonObject result = responses.get(1).getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isTrue();
        assertThat(textOf(result)).contains("mcp_server_disabled");
    }

    // --- harness -------------------------------------------------------------------------------

    private Map<String, JsonObject> schemaByMethod() throws Exception {
        try (ControlClient client = ControlClient.connect(
                ControlDiscovery.read(root), 10_000L)) {
            client.authenticate("mcp-catalog-test");
            JsonObject schema = client.call("api.schema", new JsonObject()).getAsJsonObject();
            Map<String, JsonObject> byName = new HashMap<>();
            for (JsonElement method : schema.getAsJsonArray("methods")) {
                byName.put(method.getAsJsonObject().get("name").getAsString(), method.getAsJsonObject());
            }
            return byName;
        }
    }

    private List<JsonObject> serveMcp(String... lines) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        McpStdioServer mcp = new McpStdioServer(McpStdioServer.controlBackend(root), "test", null);
        int exit = mcp.serve(new ByteArrayInputStream((String.join("\n", lines) + "\n")
            .getBytes(StandardCharsets.UTF_8)), stdout);
        assertThat(exit).isEqualTo(0);
        List<JsonObject> responses = new ArrayList<>();
        for (String line : stdout.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                responses.add(ControlJson.parseObjectStrict(line));
            }
        }
        return responses;
    }

    private static String init() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"catalog-test\"}}}";
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool + "\",\"arguments\":" + arguments + "}}";
    }

    private static String textOf(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A disposable temp tree.
                }
            });
        } catch (IOException ignored) {
            // Nothing further to do in a test teardown.
        }
    }
}
