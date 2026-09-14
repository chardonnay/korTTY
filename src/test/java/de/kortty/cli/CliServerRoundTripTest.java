package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.control.ControlApiScenarioFixtures;
import de.kortty.control.ControlApiServer;
import de.kortty.control.ControlDirectory;
import de.kortty.control.ControlErrorCode;
import de.kortty.control.ControlJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The real {@code kortty-cli} against the real control API — the only place the two halves of the
 * exit-code contract meet.
 *
 * <p>The design deliberately gives the CLI no exit-code table of its own: the server supplies
 * {@code error.data.exit} so that client and server cannot hold two disagreeing tables. That
 * invariant is untestable in either package alone. {@code CliExitCodeTest} maps a hand-written error
 * object, and the control suites assert what the server sends; only here does a code travel from
 * {@link ControlErrorCode} through a socket, through {@link ControlClient}, and out of
 * {@link KorttyCli#run} as a process exit code.
 *
 * <p>The stdout contract is the other half: on success exactly one compact JSON line on stdout and
 * nothing on stderr, so {@code $( … )} and {@code | jq} are always safe; on failure nothing at all on
 * stdout. A banner, a progress line or a stray newline would break every script in {@code cli.md}'s
 * recipes, and would do so silently.
 *
 * <p>No test here calls {@code main()} — it is the only {@code System.exit} caller — so every code is
 * asserted inside this shared JVM. The unix-domain leg is skipped, never failed, when the socket path
 * would exceed the platform limit; on Windows the same assertions run over loopback.
 */
public class CliServerRoundTripTest {

    private static final String PANE = "p1a2b";

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private ByteArrayOutputStream outBytes;

    private ByteArrayOutputStream errBytes;

    private PrintStream out;

    private PrintStream err;

    @BeforeMethod
    void startTheServer() throws IOException {
        root = Files.createTempDirectory("kt");
        ControlApiScenarioFixtures.skipIfSocketPathTooLong(
            root.resolve(ControlDirectory.DIRECTORY_NAME));
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root,
            ControlApiScenarioFixtures.twoWindowsThreeTabs(), agents);
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
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
        deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void aSuccessfulCallPrintsExactlyOneCompactJsonLineOnStdoutAndNothingOnStderr() {
        assertThat(run("pane", "list")).isEqualTo(KorttyCli.EXIT_OK);

        List<String> lines = stdout().lines().toList();
        assertWithMessage("$( … ) and | jq depend on there being exactly one line and nothing else")
            .that(lines).hasSize(1);
        JsonObject result = ControlJson.gson().fromJson(lines.get(0), JsonObject.class);
        assertThat(result.getAsJsonArray("panes").size()).isEqualTo(4);
        assertWithMessage("the default is one compact object; an indented one would still parse but"
                + " would no longer be one line, which is the property scripts rely on")
            .that(lines.get(0)).doesNotContain("\": ");
        assertWithMessage("nothing but the result may reach the caller on a success")
            .that(stderr()).isEmpty();
    }

    @Test(timeOut = 60_000)
    void everyCliEquivalentApiSchemaPublishesIsACommandThisClientAcceptsForThatVeryMethod() {
        assertThat(run("schema")).isEqualTo(KorttyCli.EXIT_OK);
        JsonObject schema = ControlJson.gson().fromJson(stdout().strip(), JsonObject.class);
        JsonArray methods = schema.getAsJsonArray("methods");
        assertWithMessage("the schema must describe the whole surface, or this proves nothing")
            .that(methods.size()).isGreaterThan(20);

        List<String> rejected = new ArrayList<>();
        for (JsonElement element : methods) {
            JsonObject method = element.getAsJsonObject();
            JsonElement cli = method.get("cli");
            if (cli == null || cli.isJsonNull() || !cli.getAsString().startsWith("kortty-cli ")) {
                continue;
            }
            String invocation = cli.getAsString();
            String[] tokens = invocation.substring("kortty-cli ".length()).trim().split("\\s+");
            try {
                CliInvocation parsed = CliArguments.parse(tokens);
                CliCommands.Command command =
                    CliCommands.find(parsed.group(), parsed.verb());
                if (command != null && command.method() != null
                        && !command.method().equals(method.get("name").getAsString())) {
                    rejected.add(invocation + " runs " + command.method() + ", not "
                        + method.get("name").getAsString());
                }
            } catch (CliSyntaxException e) {
                rejected.add(invocation + " -> " + e.message());
            }
        }

        assertWithMessage("api.schema's cli strings are the one place an agent that discovered the"
                + " API is told how to drive it from a shell, and both reference pages promise they"
                + " cannot drift from the implementation; every one must parse, and must reach the"
                + " method it is published under")
            .that(rejected)
            .isEmpty();
    }

    @Test(timeOut = 60_000)
    void theHandshakeReachesTheRealServerAndTheResultIsTheServersOwn() {
        assertThat(run("ping")).isEqualTo(KorttyCli.EXIT_OK);

        JsonObject pong = ControlJson.gson().fromJson(stdout().strip(), JsonObject.class);
        assertThat(pong.get("pong").getAsBoolean()).isTrue();
        assertWithMessage("the CLI authenticated against the running instance, so the instance it"
                + " prints must be that server's")
            .that(pong.get("instance").getAsString())
            .isEqualTo(server.endpoint().orElseThrow().instanceId());
        assertThat(stderr()).isEmpty();
    }

    @Test(timeOut = 60_000)
    void rawPrintsTheJoinedRowsOfAPaneReadInsteadOfJson() {
        assertThat(run("pane", "read", "--pane", PANE, "--recent", "--raw"))
            .isEqualTo(KorttyCli.EXIT_OK);

        assertWithMessage("--raw exists so a human or a grep sees terminal text, not a JSON array,"
                + " and it prints the rows verbatim — trailing spaces included, because a caller"
                + " matching a shell prompt depends on them")
            .that(stdout()).isEqualTo(String.join(System.lineSeparator(),
                "$ make", "ok", "user@host:~$ ") + System.lineSeparator());
        assertThat(stderr()).isEmpty();
    }

    @Test(timeOut = 60_000)
    void aWaitThatExpiresExitsFourWithAnEmptyStdout() {
        int code = run("pane", "wait-output", "--pane", PANE,
            "--contains", "this string never appears", "--timeout-ms", "400", "--poll-ms", "50");

        assertWithMessage("exit 4 is what lets a script tell 'still working' from 'it broke'; the"
                + " server supplied it in error.data.exit and the CLI must not second-guess it")
            .that(code).isEqualTo(KorttyCli.EXIT_TIMEOUT);
        assertThat(code).isEqualTo(ControlErrorCode.TIMEOUT.cliExit());
        assertWithMessage("stdout stays empty on every failure path, so a caller capturing it never"
                + " mistakes a diagnostic for a result")
            .that(stdout()).isEmpty();
        assertThat(stderr()).contains(ControlErrorCode.TIMEOUT.wire());
    }

    @Test(timeOut = 60_000)
    void anEndpointThatIsNotThereExitsThreeWithTheDocumentedSentence() throws IOException {
        Path empty = Files.createDirectory(root.resolve("nowhere"));

        int code = KorttyCli.run(new String[] {"--config-dir", empty.toString(), "ping"}, out, err);

        assertWithMessage("a missing or unreadable endpoint file is one condition from a script's"
                + " point of view: the control API is not reachable")
            .that(code).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("the korTTY control API is not running");
    }

    @Test(timeOut = 60_000)
    void aKeyNameOutsideTheVocabularyExitsTwoWithoutEverOpeningASocket() {
        int code = run("pane", "send-keys", "--pane", PANE, "frobnicate");

        assertWithMessage("a mistyped key must cost a local syntax error, not a round trip and an"
                + " audit line naming input that was never sent")
            .that(code).isEqualTo(KorttyCli.EXIT_SYNTAX);
        assertThat(code).isEqualTo(ControlErrorCode.UNKNOWN_KEY.cliExit());
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isNotEmpty();
    }

    @Test(timeOut = 60_000)
    void aRefusalTheCallerCannotFixExitsOne() {
        int code = run("pane", "get", "--pane", "p0000");

        assertThat(code).isEqualTo(KorttyCli.EXIT_FAILED);
        assertWithMessage("the exit code travelled from ControlErrorCode through error.data.exit")
            .that(code).isEqualTo(ControlErrorCode.PANE_NOT_FOUND.cliExit());
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains(ControlErrorCode.PANE_NOT_FOUND.wire());
    }

    @Test(timeOut = 60_000)
    void aServerSideSyntaxRefusalAlsoExitsTwo() {
        // A bare window id where a pane is expected: the CLI passes the selector through and the
        // server decides, which is the only way the two can agree on what a selector means.
        int code = run("pane", "get", "--pane", "w1");

        assertThat(code).isEqualTo(KorttyCli.EXIT_SYNTAX);
        assertThat(code).isEqualTo(ControlErrorCode.AMBIGUOUS_PANE.cliExit());
        assertThat(stdout()).isEmpty();
    }

    @Test(timeOut = 60_000)
    void aReservedTabVerbIsRefusedLocallyWithExitTwo() {
        int code = run("tab", "create");

        assertWithMessage("tab create is not a CLI command at all, so it must never reach the wire")
            .that(code).isEqualTo(KorttyCli.EXIT_SYNTAX);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("not implemented in this version");
    }

    @Test(timeOut = 60_000)
    void quietPrintsNothingOnEitherStreamAndStillReturnsTheCode() {
        assertThat(run("--quiet", "pane", "get", "--pane", "p0000"))
            .isEqualTo(KorttyCli.EXIT_FAILED);

        assertWithMessage("-q promises the exit code and nothing else")
            .that(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test(timeOut = 60_000)
    void prettyReproducesTheJsonRpcErrorObjectSoAScriptCanReadDataCode() {
        assertThat(run("--pretty", "pane", "get", "--pane", "p0000"))
            .isEqualTo(KorttyCli.EXIT_FAILED);

        assertThat(stdout()).isEmpty();
        JsonObject error = ControlJson.gson().fromJson(stderr(), JsonObject.class);
        assertWithMessage("recipe 7.5 of the CLI specification reads .data.code off stderr, which"
                + " needs the JSON form; §4 makes that form --pretty's job")
            .that(error.getAsJsonObject("data").get("code").getAsString())
            .isEqualTo(ControlErrorCode.PANE_NOT_FOUND.wire());
        assertThat(error.get("code").getAsInt())
            .isEqualTo(ControlErrorCode.PANE_NOT_FOUND.jsonRpcCode());
    }

    @Test(timeOut = 60_000)
    void versionAnswersWithoutContactingTheServerAtAll() {
        server.close();
        server = null;

        int code = run("--version");

        assertWithMessage("--version is the per-OS release smoke command and must work with no"
                + " korTTY running at all")
            .that(code).isEqualTo(KorttyCli.EXIT_OK);
        assertThat(stdout()).contains("korTTY control CLI");
        assertThat(stdout()).contains("(protocol 1)");
        assertThat(stderr()).isEmpty();
    }

    // --- helpers ------------------------------------------------------------------------------

    private int run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--config-dir";
        full[1] = root.toString();
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

    /**
     * Removes the temp tree deepest-entry-first.
     *
     * <p>It runs after {@code server.close()}, never instead of it: closing a channel does not unlink
     * a socket file, and a leaked socket would break the next test in this shared JVM.
     */
    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A disposable temp tree; a stubborn entry is not worth failing a teardown.
                }
            });
        } catch (IOException ignored) {
            // Nothing further to do in a test teardown.
        }
    }
}
