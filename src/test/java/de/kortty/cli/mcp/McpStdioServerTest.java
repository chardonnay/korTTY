package de.kortty.cli.mcp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.cli.CliServerException;
import de.kortty.control.ControlErrorCode;
import de.kortty.control.ControlJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

/**
 * The MCP stdio protocol against a fake control connection: the handshake, the tool listing with the
 * app down and with writes off or on, tool results and refusals, the JSON-RPC error codes, and the
 * one property every MCP host depends on — stdout carries JSON-RPC lines and nothing else.
 */
class McpStdioServerTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
        + "\"clientInfo\":{\"name\":\"Some\\nHost\\u0007 Client\",\"version\":\"1.0\"}}}";

    private static final String INITIALIZED =
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    @Test
    void initializeNegotiatesTheVersionAndAdvertisesOnlyTools() throws Exception {
        FakeBackend backend = FakeBackend.down();
        Exchange exchange = serve(backend, INIT, INITIALIZED);

        assertWithMessage("a notification is never answered").that(exchange.responses()).hasSize(1);
        JsonObject result = exchange.responses().get(0).getAsJsonObject("result");
        assertThat(result.get("protocolVersion").getAsString()).isEqualTo("2025-06-18");
        assertThat(result.getAsJsonObject("capabilities").keySet()).containsExactly("tools");
        assertThat(result.getAsJsonObject("serverInfo").get("name").getAsString())
            .isEqualTo(McpStdioServer.SERVER_NAME);
        assertThat(result.getAsJsonObject("serverInfo").get("version").getAsString())
            .isEqualTo("9.9.9");
        assertThat(result.get("instructions").getAsString()).contains("untrusted");
    }

    @Test
    void anUnknownProtocolVersionIsAnsweredWithTheNewestOneSpoken() {
        assertThat(McpStdioServer.negotiate("1999-01-01"))
            .isEqualTo(McpStdioServer.PROTOCOL_VERSIONS.get(0));
        assertThat(McpStdioServer.negotiate(null)).isEqualTo(McpStdioServer.PROTOCOL_VERSIONS.get(0));
        for (String version : McpStdioServer.PROTOCOL_VERSIONS) {
            assertThat(McpStdioServer.negotiate(version)).isEqualTo(version);
        }
    }

    @Test
    void theClientNameFromInitializeIsSanitizedCappedAndPassedToKortty() throws Exception {
        FakeBackend backend = FakeBackend.up(false);
        serve(backend, INIT, call(2, "pane_list", "{}"));

        assertThat(backend.clientNames).containsExactly("Some_Host_ Client");
        JsonObject longName = ControlJson.parseObjectStrict("{\"name\":\"" + "x".repeat(500) + "\"}");
        assertThat(McpStdioServer.sanitizeClientName(longName))
            .hasLength(McpStdioServer.MAX_CLIENT_NAME_CHARS);
        assertThat(McpStdioServer.sanitizeClientName(null))
            .isEqualTo(McpStdioServer.DEFAULT_CLIENT_NAME);
        assertThat(McpStdioServer.sanitizeClientName(ControlJson.parseObjectStrict("{\"name\":\"\\u00e4\\u00f6\"}")))
            .isEqualTo(McpStdioServer.DEFAULT_CLIENT_NAME);
    }

    @Test
    void toolsListWorksWithTheAppDownAndThenListsOnlyTheReadTools() throws Exception {
        Exchange exchange = serve(FakeBackend.down(), INIT, list(2));

        assertThat(toolNames(exchange.responses().get(1)))
            .containsExactly("pane_list", "pane_read", "pane_wait_output", "tab_list", "agent_list")
            .inOrder();
    }

    @Test
    void toolsListHidesTheWriteToolsWhileKorttyReportsThemOff() throws Exception {
        assertThat(toolNames(serve(FakeBackend.up(false), INIT, list(2)).responses().get(1)))
            .containsNoneOf("pane_send_text", "pane_run", "pane_send_keys");
    }

    @Test
    void toolsListAddsTheWriteToolsOnlyWhenKorttyReportsThemOn() throws Exception {
        Exchange exchange = serve(FakeBackend.up(true), INIT, list(2));

        assertThat(toolNames(exchange.responses().get(1)))
            .containsAtLeast("pane_send_text", "pane_run", "pane_send_keys");
        for (JsonElement tool : exchange.responses().get(1).getAsJsonObject("result")
                .getAsJsonArray("tools")) {
            JsonObject annotations = tool.getAsJsonObject().getAsJsonObject("annotations");
            boolean write = tool.getAsJsonObject().get("name").getAsString().matches(
                "pane_send_text|pane_run|pane_send_keys");
            assertWithMessage("readOnlyHint of %s", tool).that(annotations.get("readOnlyHint")
                .getAsBoolean()).isEqualTo(!write);
            assertWithMessage("destructiveHint of %s", tool).that(annotations.get("destructiveHint")
                .getAsBoolean()).isEqualTo(write);
            assertThat(tool.getAsJsonObject().get("description").getAsString()).contains("untrusted");
        }
    }

    @Test
    void toolsListTreatsARefusedHandshakeLikeAnAppThatIsDown() throws Exception {
        FakeBackend backend = FakeBackend.refusingHandshake(ControlErrorCode.MCP_SERVER_DISABLED);
        assertThat(toolNames(serve(backend, INIT, list(2)).responses().get(1)))
            .containsNoneOf("pane_send_text", "pane_run", "pane_send_keys");
    }

    @Test
    void aSuccessfulToolCallIsTextContentWithTheResultJson() throws Exception {
        FakeBackend backend = FakeBackend.up(false);
        backend.handler = (method, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("method", method);
            result.add("echo", params);
            return result;
        };
        Exchange exchange = serve(backend, INIT,
            call(2, "pane_read", "{\"pane\":\"p1a2b\",\"mode\":\"recent\",\"lines\":50}"));

        JsonObject result = exchange.responses().get(1).getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isFalse();
        JsonObject content = result.getAsJsonArray("content").get(0).getAsJsonObject();
        assertThat(content.get("type").getAsString()).isEqualTo("text");
        JsonObject text = ControlJson.parseObjectStrict(content.get("text").getAsString());
        assertThat(text.get("method").getAsString()).isEqualTo("pane.read");
        assertThat(text.getAsJsonObject("echo").get("pane").getAsString()).isEqualTo("p1a2b");
        assertThat(text.getAsJsonObject("echo").get("lines").getAsLong()).isEqualTo(50L);
    }

    @Test
    void aRefusalByKorttyIsAToolErrorThatNamesTheReason() throws Exception {
        FakeBackend backend = FakeBackend.up(false);
        backend.handler = (method, params) -> {
            throw new IllegalStateException(new CliServerException(-32008,
                "method_not_allowed_for_mcp", "MCP write tools are switched off in korTTY", 3,
                reason("write_tools_disabled")));
        };
        Exchange exchange = serve(backend, INIT,
            call(2, "pane_run", "{\"pane\":\"p1a2b\",\"command\":\"ls\"}"));

        JsonObject result = exchange.responses().get(1).getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isTrue();
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertThat(text).contains("switched off");
        assertThat(text).contains("method_not_allowed_for_mcp");
        assertThat(text).contains("write_tools_disabled");
    }

    @Test
    void aStoppedAppAndATimeoutAreToolErrorsNotProtocolErrors() throws Exception {
        Exchange down = serve(FakeBackend.down(), INIT, call(2, "pane_list", "{}"));
        JsonObject downResult = down.responses().get(1).getAsJsonObject("result");
        assertThat(downResult.get("isError").getAsBoolean()).isTrue();
        assertThat(textOf(downResult)).isEqualTo(McpStdioServer.NOT_RUNNING);

        FakeBackend slow = FakeBackend.up(false);
        slow.timeout = true;
        Exchange timedOut = serve(slow, INIT, call(2, "tab_list", "{}"));
        JsonObject timeoutResult = timedOut.responses().get(1).getAsJsonObject("result");
        assertThat(timeoutResult.get("isError").getAsBoolean()).isTrue();
        assertThat(textOf(timeoutResult)).contains("did not answer");
    }

    @Test
    void aGateVerdictAtTheHandshakeIsAToolError() throws Exception {
        FakeBackend backend = FakeBackend.refusingHandshake(ControlErrorCode.BLOCKED_BY_POLICY);
        JsonObject result = serve(backend, INIT, call(2, "pane_list", "{}")).responses().get(1)
            .getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isTrue();
        assertThat(textOf(result)).contains("blocked_by_policy");
    }

    @Test
    void badArgumentsAreToolErrorsAndNeverReachKortty() throws Exception {
        FakeBackend backend = FakeBackend.up(true);
        Exchange exchange = serve(backend, INIT,
            call(2, "pane_read", "{\"pane\":\"p1\",\"bogus\":1}"),
            call(3, "pane_read", "{}"),
            call(4, "pane_read", "{\"pane\":\"p1\",\"mode\":\"everything\"}"),
            call(5, "pane_wait_output", "{\"pane\":\"p1\"}"),
            call(6, "pane_wait_output", "{\"pane\":\"p1\",\"regex\":\"a\",\"contains\":\"b\"}"),
            call(7, "pane_read", "{\"pane\":\"p1\",\"lines\":1.5}"),
            call(8, "pane_send_keys", "{\"pane\":\"p1\",\"keys\":[]}"));

        for (int i = 1; i <= 7; i++) {
            JsonObject result = exchange.responses().get(i).getAsJsonObject("result");
            assertWithMessage("response %s", exchange.responses().get(i))
                .that(result.get("isError").getAsBoolean()).isTrue();
        }
        assertThat(backend.methods).isEmpty();
    }

    @Test
    void anUnknownToolIsInvalidParams() throws Exception {
        JsonObject response = serve(FakeBackend.up(true), INIT, call(2, "agent_prompt", "{}"))
            .responses().get(1);
        assertThat(response.getAsJsonObject("error").get("code").getAsInt())
            .isEqualTo(McpStdioServer.INVALID_PARAMS);
        assertThat(response.get("id").getAsInt()).isEqualTo(2);
    }

    @Test
    void aMalformedLineIsAParseErrorAndTheServerKeepsServing() throws Exception {
        Exchange exchange = serve(FakeBackend.down(), "{not json", "[1,2]",
            "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"ping\"}");

        assertThat(exchange.responses()).hasSize(3);
        assertThat(exchange.responses().get(0).getAsJsonObject("error").get("code").getAsInt())
            .isEqualTo(McpStdioServer.PARSE_ERROR);
        assertThat(exchange.responses().get(0).get("id").isJsonNull()).isTrue();
        assertThat(exchange.responses().get(1).getAsJsonObject("error").get("code").getAsInt())
            .isEqualTo(McpStdioServer.INVALID_REQUEST);
        assertThat(exchange.responses().get(2).get("id").getAsString()).isEqualTo("a");
        assertThat(exchange.responses().get(2).getAsJsonObject("result").size()).isEqualTo(0);
        assertThat(exchange.exitCode()).isEqualTo(0);
    }

    @Test
    void anUnknownMethodIsMethodNotFoundAndAnUnknownNotificationIsIgnored() throws Exception {
        Exchange exchange = serve(FakeBackend.down(),
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}");

        assertThat(exchange.responses()).hasSize(1);
        assertThat(exchange.responses().get(0).getAsJsonObject("error").get("code").getAsInt())
            .isEqualTo(McpStdioServer.METHOD_NOT_FOUND);
        assertThat(exchange.responses().get(0).get("id").getAsInt()).isEqualTo(7);
    }

    @Test
    void stdoutCarriesOnlyJsonRpcLinesAndStderrNeverEchoesArgumentsOrResults() throws Exception {
        FakeBackend backend = FakeBackend.up(true);
        backend.handler = (method, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("screen", "line one\nSECRET-RESULT\r\nline three");
            return result;
        };
        Exchange exchange = serve(backend, INIT, INITIALIZED, list(2),
            call(3, "pane_send_text", "{\"pane\":\"p1\",\"text\":\"SECRET-ARG\\nrm -rf /\"}"),
            "garbage", "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"nope\"}");

        String stdout = exchange.stdout();
        assertThat(stdout).endsWith("\n");
        for (String line : stdout.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            JsonObject message = ControlJson.parseObjectStrict(line);
            assertWithMessage("line %s", line).that(message.get("jsonrpc").getAsString()).isEqualTo("2.0");
        }
        assertThat(stdout.split("\n")).hasLength(5);
        assertThat(exchange.stderr()).contains("tools/call");
        assertThat(exchange.stderr()).contains("pane_send_text");
        assertThat(exchange.stderr()).doesNotContain("SECRET-ARG");
        assertThat(exchange.stderr()).doesNotContain("SECRET-RESULT");
        assertThat(exchange.stderr()).doesNotContain("rm -rf");
    }

    @Test
    void aWaitingToolGetsItsOwnWaitPlusSlackAsTheClientDeadline() throws Exception {
        McpToolCatalog.McpTool wait = McpToolCatalog.find("pane_wait_output");
        JsonObject params = ControlJson.parseObjectStrict("{\"timeout_ms\":1000}");
        assertThat(McpStdioServer.timeoutFor(wait, params))
            .isEqualTo(1000L + McpStdioServer.WAIT_SLACK_MILLIS);
        assertThat(McpStdioServer.timeoutFor(McpToolCatalog.find("pane_list"), new JsonObject()))
            .isEqualTo(McpStdioServer.CALL_TIMEOUT_MILLIS);
    }

    @Test
    void aWriteToolWaitsLongerThanTheUserHasToAnswerKorttysConsentPrompt() {
        for (String write : List.of("pane_send_text", "pane_run", "pane_send_keys")) {
            assertThat(McpStdioServer.timeoutFor(McpToolCatalog.find(write), new JsonObject()))
                .isGreaterThan(de.kortty.control.McpWriteConsent.PROMPT_TIMEOUT_MILLIS);
        }
    }

    // --- harness -------------------------------------------------------------------------------

    private record Exchange(List<JsonObject> responses, String stdout, String stderr, int exitCode) {
    }

    private static Exchange serve(FakeBackend backend, String... lines) throws Exception {
        String input = String.join("\n", lines) + "\n";
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(backend, "9.9.9",
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        int exit = server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), stdout);
        String out = stdout.toString(StandardCharsets.UTF_8);
        List<JsonObject> responses = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (!line.isEmpty()) {
                responses.add(ControlJson.parseObjectStrict(line));
            }
        }
        return new Exchange(responses, out, stderr.toString(StandardCharsets.UTF_8), exit);
    }

    private static String list(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}";
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool + "\",\"arguments\":" + arguments + "}}";
    }

    private static List<String> toolNames(JsonObject response) {
        List<String> names = new ArrayList<>();
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        tools.forEach(tool -> names.add(tool.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static JsonObject reason(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        return data;
    }

    private static String textOf(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** A control connection that is down, refuses the handshake, or answers from a handler. */
    private static final class FakeBackend implements McpStdioServer.Backend {

        final List<String> clientNames = new ArrayList<>();

        final List<String> methods = new ArrayList<>();

        boolean reachable = true;

        boolean timeout;

        ControlErrorCode handshakeRefusal;

        boolean writes;

        BiFunction<String, JsonObject, JsonElement> handler = (method, params) -> new JsonObject();

        static FakeBackend down() {
            FakeBackend backend = new FakeBackend();
            backend.reachable = false;
            return backend;
        }

        static FakeBackend up(boolean writes) {
            FakeBackend backend = new FakeBackend();
            backend.writes = writes;
            return backend;
        }

        static FakeBackend refusingHandshake(ControlErrorCode code) {
            FakeBackend backend = new FakeBackend();
            backend.handshakeRefusal = code;
            return backend;
        }

        @Override
        public McpStdioServer.Connection open(String clientName, long timeoutMillis)
                throws IOException, CliServerException {
            clientNames.add(clientName);
            if (!reachable) {
                throw new IOException("connection refused");
            }
            if (handshakeRefusal != null) {
                throw new CliServerException(handshakeRefusal.jsonRpcCode(), handshakeRefusal.wire(),
                    "refused at the handshake", handshakeRefusal.cliExit(), new JsonObject());
            }
            JsonObject hello = new JsonObject();
            hello.addProperty("client_kind", "mcp");
            hello.addProperty("mcp_write_tools", writes);
            return new McpStdioServer.Connection() {
                @Override
                public JsonObject hello() {
                    return hello;
                }

                @Override
                public JsonElement call(String method, JsonObject params)
                        throws IOException, CliServerException {
                    methods.add(method);
                    if (timeout) {
                        throw new SocketTimeoutException("deadline");
                    }
                    try {
                        return handler.apply(method, params);
                    } catch (IllegalStateException e) {
                        if (e.getCause() instanceof CliServerException refusal) {
                            throw refusal;
                        }
                        throw e;
                    }
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
