package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.control.ControlJson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

/**
 * The stdout and stderr contract.
 *
 * <p>The invariant these assertions protect is that {@code $( … )} and {@code | jq} are always safe:
 * one compact line of result on stdout, nothing else on stdout ever, and every failure on stderr.
 */
class CliOutputTest {

    @Test
    void aSuccessPrintsExactlyOneCompactLineOnStdoutAndNothingOnStderr() throws Exception {
        Streams streams = new Streams();

        CliOutput.printResult(streams.out, result("{\"pane\":{\"pane_id\":\"p1a2b\"}}"), false, false);

        assertThat(streams.out()).isEqualTo("{\"pane\":{\"pane_id\":\"p1a2b\"}}"
            + System.lineSeparator());
        assertThat(streams.err()).isEmpty();
    }

    @Test
    void prettyIndentsTheSameDocumentRatherThanChangingIt() throws Exception {
        Streams streams = new Streams();

        CliOutput.printResult(streams.out, result("{\"ok\":true}"), false, true);

        assertThat(streams.out().strip().lines().count()).isGreaterThan(1L);
        assertWithMessage("--pretty must not alter the document, only its layout")
            .that(ControlJson.parseObjectStrict(streams.out().strip()).get("ok").getAsBoolean())
            .isTrue();
    }

    @Test
    void rawPrintsTheJoinedLinesOfAPaneRead() throws Exception {
        Streams streams = new Streams();

        CliOutput.printResult(streams.out,
            result("{\"pane_id\":\"p1\",\"lines\":[\"first\",\"second\"]}"), true, false);

        assertThat(streams.out()).isEqualTo("first" + System.lineSeparator() + "second"
            + System.lineSeparator());
    }

    @Test
    void rawPrintsTheTextOfAnAgentExplain() throws Exception {
        Streams streams = new Streams();

        CliOutput.printResult(streams.out, result("{\"explain\":\"Claude Code is BLOCKED\"}"), true,
            false);

        assertThat(streams.out()).isEqualTo("Claude Code is BLOCKED" + System.lineSeparator());
    }

    @Test
    void rawFallsBackToJsonForAResultThatCarriesNoText() throws Exception {
        Streams streams = new Streams();

        CliOutput.printResult(streams.out, result("{\"ok\":true}"), true, false);

        assertWithMessage("a script piping into jq must still get JSON")
            .that(streams.out())
            .isEqualTo("{\"ok\":true}" + System.lineSeparator());
    }

    @Test
    void aFailurePrintsNothingOnStdoutAndOneLineOnStderr() throws Exception {
        Streams streams = new Streams();

        CliOutput.printError(streams.err, notFound(), false);

        assertThat(streams.out()).isEmpty();
        assertThat(streams.err()).isEqualTo("kortty-cli: No pane p1a2b is open (pane_not_found)"
            + System.lineSeparator());
        assertThat(streams.err().lines().count()).isEqualTo(1L);
    }

    @Test
    void aPrettyFailureReproducesTheJsonRpcErrorObject() throws Exception {
        Streams streams = new Streams();

        CliOutput.printError(streams.err, notFound(), true);

        JsonObject printed = ControlJson.parseObjectStrict(streams.err().strip());
        assertThat(printed.get("code").getAsInt()).isEqualTo(-32012);
        assertThat(printed.getAsJsonObject("data").get("code").getAsString())
            .isEqualTo("pane_not_found");
        assertThat(printed.getAsJsonObject("data").get("exit").getAsInt()).isEqualTo(1);
        assertThat(streams.out()).isEmpty();
    }

    @Test
    void quietPrintsNothingAtAllOnEitherStream() throws Exception {
        Streams streams = new Streams();

        // --quiet is enforced by KorttyCli not calling these at all; assert the equivalent here.
        assertThat(KorttyCli.run(new String[] {"--quiet", "--version"}, streams.out, streams.err))
            .isEqualTo(KorttyCli.EXIT_OK);
        assertThat(KorttyCli.run(new String[] {"-q", "no-such-command"}, streams.out, streams.err))
            .isEqualTo(KorttyCli.EXIT_SYNTAX);

        assertThat(streams.out()).isEmpty();
        assertThat(streams.err()).isEmpty();
    }

    @Test
    void theVersionBannerNamesTheProtocolAndNeedsNoServer() throws Exception {
        Streams streams = new Streams();

        assertThat(KorttyCli.run(new String[] {"--version"}, streams.out, streams.err))
            .isEqualTo(KorttyCli.EXIT_OK);

        assertThat(streams.out()).startsWith("korTTY control CLI ");
        assertThat(streams.out()).contains("(protocol 1)");
        assertThat(streams.err()).isEmpty();
    }

    @Test
    void helpGoesToStdoutAndExitsZeroAtEveryLevel() throws Exception {
        for (String[] args : new String[][] {{"--help"}, {"pane", "--help"},
                {"pane", "read", "--help"}}) {
            Streams streams = new Streams();
            assertWithMessage("exit code of '%s'", String.join(" ", args))
                .that(KorttyCli.run(args, streams.out, streams.err))
                .isEqualTo(KorttyCli.EXIT_OK);
            assertThat(streams.out()).contains("kortty-cli");
            assertThat(streams.err()).isEmpty();
        }
    }

    @Test
    void theTopLevelHelpSaysThereIsNoTokenFlag() {
        assertThat(CliUsage.top()).contains("no --token flag");
    }

    @Test
    void anUnknownGroupFallsBackToTheTopLevelHelpRatherThanFailing() {
        assertThat(CliUsage.group("nope")).isEqualTo(CliUsage.top());
        assertThat(CliUsage.verb("pane", "nope")).isEqualTo(CliUsage.group("pane"));
    }

    private static CliServerException notFound() {
        JsonObject data = new JsonObject();
        data.addProperty("code", "pane_not_found");
        data.addProperty("retryable", false);
        data.addProperty("exit", 1);
        return new CliServerException(-32012, "pane_not_found", "No pane p1a2b is open", 1, data);
    }

    private static JsonElement result(String json) throws Exception {
        return ControlJson.parseObjectStrict(json);
    }

    /** A pair of capturing streams, so a test can assert that one of them stayed empty. */
    private static final class Streams {

        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

        private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        private final PrintStream out;

        private final PrintStream err;

        Streams() throws UnsupportedEncodingException {
            this.out = new PrintStream(outBytes, true, StandardCharsets.UTF_8.name());
            this.err = new PrintStream(errBytes, true, StandardCharsets.UTF_8.name());
        }

        String out() {
            out.flush();
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            err.flush();
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
