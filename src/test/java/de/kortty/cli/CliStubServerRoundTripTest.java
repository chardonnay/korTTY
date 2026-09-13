package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What {@code kortty-cli} actually puts on the wire, over both transports.
 *
 * <p>The assertions are the client half of the handshake contract: {@code auth} is the first request
 * on every connection, it carries the token that was in {@code endpoint.json} and nothing else does,
 * and every request is exactly one line. Those are the properties the server relies on and the ones
 * a hand-written client gets wrong.
 *
 * <p>The unix-domain leg is skipped rather than failed when the platform cannot take it: Windows has
 * no {@code AF_UNIX} listener here, and a deep temp directory would blow the measured 106-character
 * Linux and ~102-character macOS socket path limits. The loopback leg runs everywhere.
 */
class CliStubServerRoundTripTest {

    private Path root;

    private Path configDir;

    private StubControlServer server;

    private ByteArrayOutputStream outBytes;

    private ByteArrayOutputStream errBytes;

    private PrintStream out;

    private PrintStream err;

    @BeforeMethod
    void createTempTree() throws IOException {
        root = StubControlServer.newTempRoot();
        configDir = root.resolve("h");
        Files.createDirectories(configDir);
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    }

    @AfterMethod
    void stopServerAndDeleteTempTree() {
        if (server != null) {
            server.close();
            server = null;
        }
        StubControlServer.deleteTree(root);
    }

    @Test
    void overAUnixSocketTheCliAuthenticatesFirstAndExitsZero() throws Exception {
        Path socket = root.resolve("c.sock");
        StubControlServer.requireBindableSocketPath(socket);
        server = StubControlServer.onUnixSocket(socket, StubControlServer.TOKEN);
        server.publishEndpoint(configDir);
        server.replyWith(paneList());

        assertThat(run("pane", "list")).isEqualTo(KorttyCli.EXIT_OK);

        assertHandshake();
        assertThat(stdout()).isEqualTo("{\"panes\":[{\"pane_id\":\"p1a2b\"}]}"
            + System.lineSeparator());
        assertThat(stderr()).isEmpty();
    }

    @Test
    void overLoopbackTheCliAuthenticatesFirstAndExitsZero() throws Exception {
        startLoopback();
        server.replyWith(paneList());

        assertThat(run("pane", "list")).isEqualTo(KorttyCli.EXIT_OK);

        assertHandshake();
        assertThat(stdout()).contains("p1a2b");
    }

    @Test
    void theTokenTravelsOnlyInTheAuthRequestAndNeverAppearsInTheOutput() throws Exception {
        startLoopback();
        server.replyWith(paneList());

        run("pane", "list");

        List<JsonObject> requests = server.requests();
        for (int index = 1; index < requests.size(); index++) {
            assertWithMessage("request %s must not repeat the token", index)
                .that(requests.get(index).toString())
                .doesNotContain(StubControlServer.TOKEN);
        }
        assertThat(stdout()).doesNotContain(StubControlServer.TOKEN);
        assertThat(stderr()).doesNotContain(StubControlServer.TOKEN);
    }

    @Test
    void everyRequestIsOneWellFormedJsonRpcLine() throws Exception {
        startLoopback();
        server.replyWith(paneList());

        run("pane", "read", "--pane", "p1a2b", "--recent", "--lines", "3");

        for (JsonObject request : server.requests()) {
            assertThat(request.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertWithMessage("a notification would be rejected as invalid_request")
                .that(request.has("id"))
                .isTrue();
            assertThat(request.get("method").getAsJsonPrimitive().isString()).isTrue();
            assertThat(request.toString()).doesNotContain("\n");
        }
    }

    @Test
    void theRequestCarriesTheParametersTheVerbMappedRatherThanTheFlagsTheUserTyped()
            throws Exception {
        startLoopback();
        server.replyWith(new JsonObject());

        run("pane", "read", "--pane", "p1a2b", "--recent", "--lines", "3");

        JsonObject request = server.requests().get(1);
        assertThat(request.get("method").getAsString()).isEqualTo("pane.read");
        JsonObject params = request.getAsJsonObject("params");
        assertThat(params.get("pane").getAsString()).isEqualTo("p1a2b");
        assertThat(params.get("mode").getAsString()).isEqualTo("recent");
        assertThat(params.get("lines").getAsInt()).isEqualTo(3);
    }

    @Test
    void rawPrintsTheJoinedLinesOfAPaneReadOverTheRealTransport() throws Exception {
        startLoopback();
        JsonObject read = new JsonObject();
        read.addProperty("pane_id", "p1a2b");
        JsonArray lines = new JsonArray();
        lines.add("$ make test");
        lines.add("ok");
        read.add("lines", lines);
        server.replyWith(read);

        assertThat(run("pane", "read", "--pane", "p1a2b", "--recent", "--raw"))
            .isEqualTo(KorttyCli.EXIT_OK);

        assertThat(stdout()).isEqualTo("$ make test" + System.lineSeparator() + "ok"
            + System.lineSeparator());
    }

    @Test
    void eventsPrintsOneEventPerLineAndStopsAtTheRequestedCount() throws Exception {
        startLoopback();
        JsonObject subscribed = new JsonObject();
        subscribed.addProperty("subscription_id", "s1");
        server.replyWith(subscribed);
        server.emitEvents(3);

        assertThat(run("events", "--count", "2")).isEqualTo(KorttyCli.EXIT_OK);

        List<String> printed = stdout().lines().toList();
        assertWithMessage("--count bounds the stream; the subscribe result is never printed")
            .that(printed)
            .hasSize(2);
        assertThat(printed.get(0)).contains("agent.state_changed");
        assertThat(printed.get(0)).doesNotContain("subscription_id");
    }

    @Test
    void aCurrentSelectorResolvesThroughPaneResolveBeforeTheRealCall() throws Exception {
        startLoopback();
        JsonObject resolved = new JsonObject();
        JsonObject pane = new JsonObject();
        pane.addProperty("pane_id", "p7f31");
        resolved.add("pane", pane);
        resolved.addProperty("matched_pid", ProcessHandle.current().pid());
        server.replyWith(resolved);

        assertThat(run("pane", "get", "--current")).isEqualTo(KorttyCli.EXIT_OK);

        List<JsonObject> requests = server.requests();
        assertThat(requests.get(1).get("method").getAsString()).isEqualTo("pane.resolve");
        JsonArray pids = requests.get(1).getAsJsonObject("params").getAsJsonArray("pids");
        assertThat(pids.get(0).getAsLong()).isEqualTo(ProcessHandle.current().pid());
        assertThat(requests.get(2).get("method").getAsString()).isEqualTo("pane.get");
        assertWithMessage("the sentinel must have been replaced by the resolved pane id")
            .that(requests.get(2).getAsJsonObject("params").get("pane").getAsString())
            .isEqualTo("p7f31");
    }

    private void startLoopback() throws IOException {
        server = StubControlServer.onLoopback(StubControlServer.TOKEN);
        server.publishEndpoint(configDir);
    }

    private void assertHandshake() {
        List<JsonObject> requests = server.requests();
        assertThat(requests).isNotEmpty();
        JsonObject auth = requests.get(0);
        assertWithMessage("the first request on a connection must be auth")
            .that(auth.get("method").getAsString())
            .isEqualTo("auth");
        assertThat(auth.getAsJsonObject("params").get("token").getAsString())
            .isEqualTo(StubControlServer.TOKEN);
        assertThat(auth.getAsJsonObject("params").get("client").getAsString())
            .isEqualTo(KorttyCli.CLIENT_NAME);
    }

    private static JsonObject paneList() {
        JsonObject pane = new JsonObject();
        pane.addProperty("pane_id", "p1a2b");
        JsonArray panes = new JsonArray();
        panes.add(pane);
        JsonObject result = new JsonObject();
        result.add("panes", panes);
        return result;
    }

    private int run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--config-dir";
        full[1] = configDir.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return KorttyCli.run(full, out, err);
    }

    private String stdout() {
        out.flush();
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        err.flush();
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
