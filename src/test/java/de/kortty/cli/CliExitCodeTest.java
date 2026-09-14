package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.kortty.control.ControlJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The documented exit codes, driven end to end through a stub server that chooses the
 * {@code error.data.exit} it answers with.
 *
 * <p>The server supplies the exit code precisely so that the two sides cannot hold disagreeing
 * tables, and these assertions are what prove the CLI honours the field rather than re-deriving it.
 * Every case runs inside the shared test JVM because {@link KorttyCli#run} returns the code —
 * <strong>no test here calls {@code main()}</strong>, which would take the JVM down with it.
 *
 * <p>The loopback transport is used throughout: it behaves identically on all three platforms and is
 * immune to the unix-domain path-length limit, which the round-trip test covers separately.
 */
class CliExitCodeTest {

    private Path root;

    private Path configDir;

    private StubControlServer server;

    private ByteArrayOutputStream outBytes;

    private ByteArrayOutputStream errBytes;

    private PrintStream out;

    private PrintStream err;

    @BeforeMethod
    void startServer() throws IOException {
        root = StubControlServer.newTempRoot();
        configDir = root.resolve("home");
        Files.createDirectories(configDir);
        server = StubControlServer.onLoopback(StubControlServer.TOKEN);
        server.publishEndpoint(configDir);
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    }

    @AfterMethod
    void stopServer() {
        if (server != null) {
            server.close();
        }
        StubControlServer.deleteTree(root);
    }

    @Test
    void aResultExitsZeroAndPutsOneLineOnStdout() {
        JsonObject result = new JsonObject();
        result.addProperty("pong", true);
        server.replyWith(result);

        assertThat(run("ping")).isEqualTo(KorttyCli.EXIT_OK);
        assertThat(stdout()).isEqualTo("{\"pong\":true}" + System.lineSeparator());
        assertThat(stderr()).isEmpty();
    }

    @Test
    void aFailureTheCallerCannotFixExitsOneAndLeavesStdoutEmpty() {
        server.failWith(-32012, "pane_not_found", "No pane p1a2b is open", 1);

        assertThat(run("pane", "get", "--pane", "p1a2b")).isEqualTo(KorttyCli.EXIT_FAILED);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("pane_not_found");
    }

    @Test
    void aServerSideUsageErrorExitsTwoJustLikeALocalOne() {
        server.failWith(-32602, "invalid_params", "Parameter 'lines' must be between 1 and 10000", 2);

        assertThat(run("pane", "read", "--focused", "--lines", "99999"))
            .isEqualTo(KorttyCli.EXIT_SYNTAX);
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aLocalSyntaxErrorExitsTwoWithoutContactingTheServer() {
        assertThat(run("pane", "send-keys", "--focused", "entre")).isEqualTo(KorttyCli.EXIT_SYNTAX);

        assertWithMessage("a mistyped key must never reach the wire or the audit log")
            .that(server.requests())
            .isEmpty();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("entre");
    }

    @Test
    void aRefusedClientExitsThree() {
        server.failWith(-32002, "control_api_disabled", "The korTTY control API is switched off", 3);

        assertThat(run("ping")).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aMissingEndpointFileExitsThreeWithTheSettingsPath() throws IOException {
        Path empty = root.resolve("empty");
        Files.createDirectories(empty);

        assertThat(KorttyCli.run(new String[] {"--config-dir", empty.toString(), "ping"}, out, err))
            .isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(stderr()).contains("Control API");
    }

    /**
     * korTTY exiting without unlinking its socket leaves an {@code endpoint.json} pointing at nothing.
     *
     * <p>This runs over the unix-domain socket on purpose. A closed loopback port is not a stable
     * "nothing": the kernel hands ephemeral ports back out within the same JVM, and a port that has
     * been re-bound completes the handshake from its backlog even before its owner accepts — so the
     * client connects, waits and reports a timeout instead. A deleted socket file cannot be recycled,
     * so the refusal is the same on every run.
     */
    @Test
    void aStaleEndpointFileWhoseListenerIsGoneExitsThreeWithoutNamingTheToken() throws IOException {
        Path socketPath = root.resolve("s.sock");
        StubControlServer.requireBindableSocketPath(socketPath);
        server.close();
        server = StubControlServer.onUnixSocket(socketPath, StubControlServer.TOKEN);
        server.publishEndpoint(configDir);
        server.close();
        Files.deleteIfExists(socketPath);

        assertThat(run("ping")).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(stderr()).doesNotContain(StubControlServer.TOKEN);
        assertThat(stderr()).contains("korTTY may have exited");
    }

    /**
     * A stale {@code endpoint.json} can name an address that is not korTTY at all. However the host
     * answers it, the endpoint is unreachable — never a wait a larger {@code --timeout} could have
     * saved, and never a hang.
     *
     * <p>The address is {@code 192.0.2.1}, reserved by RFC 5737 for documentation and routed nowhere.
     * Note what this does and does not pin: it pins the exit code and that the client returns at all.
     * It does <em>not</em> prove {@code ControlClient}'s connect bound, because a host that answers
     * "no route" immediately — as this sandbox does — never reaches the bound. The bound matters on a
     * host that silently drops instead, where an unbounded connect would never return; the elapsed
     * assertion below is what would catch that regression there.
     */
    @Test
    void anAddressThatIsNotKorttyIsUnreachableRatherThanATimeout() throws IOException {
        server.close();
        writeEndpointHost("192.0.2.1", 9);

        long startedAt = System.nanoTime();
        int code = run("ping");
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertWithMessage("an address nothing answers on is unreachable, not a wait that a larger"
                + " --timeout could have saved")
            .that(code).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertWithMessage("the client must return; on a host that drops rather than refuses, an"
                + " unbounded connect would never come back")
            .that(elapsedMillis).isLessThan(30_000L);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).doesNotContain(StubControlServer.TOKEN);
    }

    /** Repoints the published endpoint file at {@code host:port}. */
    private void writeEndpointHost(String host, int port) throws IOException {
        Path file = configDir.resolve("control").resolve("endpoint.json");
        String json = Files.readString(file)
            .replaceAll("\"port\"\\s*:\\s*\\d+", "\"port\": " + port)
            .replaceAll("\"host\"\\s*:\\s*\"[^\"]*\"", "\"host\": \"" + host + "\"");
        Files.writeString(file, json);
    }

    @Test
    void aWrongTokenIsAnUnauthorizedExitThree() throws IOException {
        server.close();
        server = StubControlServer.onLoopback("a-completely-different-43-character-token--");
        server.publishEndpoint(configDir);
        // The published file now carries the server's token, so overwrite it with a stale one.
        Files.writeString(configDir.resolve("control").resolve("endpoint.json"),
            server.descriptor().toJson().replace(server.descriptor().token(),
                StubControlServer.TOKEN),
            StandardCharsets.UTF_8);

        assertThat(run("ping")).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(stderr()).contains("unauthorized");
    }

    @Test
    void anExpiredWaitExitsFour() {
        server.failWith(-32040, "timeout", "The wait expired after 30000 ms", 4);

        assertThat(run("agent", "wait", "--pane", "p7f31", "--until", "done", "--timeout-ms", "30000"))
            .isEqualTo(KorttyCli.EXIT_TIMEOUT);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("timeout");
    }

    @Test
    void anErrorWithoutAnExitFieldFallsBackToTheLocalTable() {
        server.failWith(-32030, "last_pane", "The tab's last pane cannot be closed", 0);

        assertWithMessage("last_pane's documented exit code is 1")
            .that(run("pane", "close", "--pane", "p1a2b"))
            .isEqualTo(KorttyCli.EXIT_FAILED);
    }

    @Test
    void anExpiredClientDeadlineExitsFourRatherThanThree() {
        server.stallAfterAuth();

        assertWithMessage("neither transport has a read timeout, so the watchdog is the only way out")
            .that(run("--timeout", "250", "ping"))
            .isEqualTo(KorttyCli.EXIT_TIMEOUT);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("--timeout");
    }

    @Test
    void aCurrentSelectorThatMatchesNoPaneExitsOneAndNeverFallsBackToTheFocusedPane() {
        JsonObject noMatch = new JsonObject();
        noMatch.add("pane", JsonNull.INSTANCE);
        noMatch.add("matched_pid", JsonNull.INSTANCE);
        server.replyWith(noMatch);

        assertThat(run("pane", "get", "--current")).isEqualTo(KorttyCli.EXIT_FAILED);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("not running inside a korTTY local shell pane");
        assertWithMessage("only pane.resolve may have been sent; no pane.get against a guess")
            .that(server.requests().get(server.requests().size() - 1).get("method").getAsString())
            .isEqualTo("pane.resolve");
    }

    /**
     * Silence is something the user asks for, never something a payload does by accident: the pane
     * text here happens to spell the short quiet flag, and the diagnostic for the unknown flag on the
     * same line is mandatory. A probe that matched the spelling anywhere in {@code argv} left the user
     * with a bare exit 2 and nothing on stderr.
     */
    @Test
    void aPayloadThatSpellsTheQuietFlagStillGetsItsSyntaxDiagnostic() {
        assertThat(run("pane", "send-text", "--focused", "--text", "-q", "--nope"))
            .isEqualTo(KorttyCli.EXIT_SYNTAX);

        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("--nope");
        assertThat(server.requests()).isEmpty();
    }

    /**
     * {@code --count} is counted down with an {@code int}, so an out-of-range value has to be refused
     * before the socket is opened. It used to parse, authenticate, subscribe and only then throw an
     * unchecked NumberFormatException out of {@code run} — a stack trace, exit 1, and a subscription
     * logged for a client that had already died.
     */
    @Test
    void anOutOfRangeEventCountExitsTwoBeforeAnythingIsSubscribed() {
        assertThat(run("events", "--count", "3000000000")).isEqualTo(KorttyCli.EXIT_SYNTAX);

        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("--count");
        assertWithMessage("no auth and no events.subscribe may have reached the server")
            .that(server.requests())
            .isEmpty();
    }

    @Test
    void quietPrintsNothingOnAnyExitCode() {
        server.failWith(-32012, "pane_not_found", "No pane p1a2b is open", 1);

        assertThat(run("-q", "pane", "get", "--pane", "p1a2b")).isEqualTo(KorttyCli.EXIT_FAILED);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test
    void aPrettyFailurePutsTheErrorObjectOnStderrForJq() throws Exception {
        server.failWith(-32013, "agent_not_found", "No coding agent is registered for p1a2b", 1);

        assertThat(run("--pretty", "agent", "get", "--pane", "p1a2b"))
            .isEqualTo(KorttyCli.EXIT_FAILED);
        assertThat(ControlJson.parseObjectStrict(stderr().strip())
            .getAsJsonObject("data").get("code").getAsString()).isEqualTo("agent_not_found");
    }

    @Test
    void anExplicitVersionNeverOpensASocket() {
        assertThat(KorttyCli.run(new String[] {"--version"}, out, err)).isEqualTo(KorttyCli.EXIT_OK);

        assertThat(server.requests()).isEmpty();
        assertThat(stdout()).contains("protocol 1");
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
