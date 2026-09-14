package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

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
 * What the CLI writes must survive the console it is written to.
 *
 * <p>Two separate rules, for two different reasons. Everything the CLI composes itself — usage
 * screens and diagnostics — is plain ASCII, so it stays readable even where the terminal is a legacy
 * code page that has no em dash and no ellipsis. Everything that merely passes through it — a pane's
 * own text, a message the server wrote — is carried as UTF-8, because replacing those characters
 * would corrupt the very content the caller asked for.
 */
class CliEncodingTest {

    private Path root;

    private Path configDir;

    @BeforeMethod
    void setUp() throws IOException {
        root = StubControlServer.newTempRoot();
        configDir = root.resolve("home");
        Files.createDirectories(configDir);
    }

    @AfterMethod
    void tearDown() {
        StubControlServer.deleteTree(root);
    }

    @Test
    void everyUsageScreenIsPlainAsciiSoALegacyConsoleCanRenderIt() {
        assertAscii("the top-level usage screen", CliUsage.top());
        for (CliCommands.Command command : CliCommands.commands()) {
            assertAscii("the usage screen of group '" + command.group() + "'",
                CliUsage.group(command.group()));
            if (command.verb() != null) {
                assertAscii("the usage screen of '" + command.group() + " " + command.verb() + "'",
                    CliUsage.verb(command.group(), command.verb()));
            }
        }
    }

    @Test
    void theDiagnosticsTheClientComposesItselfArePlainAscii() {
        assertAscii("the diagnostic for an unreachable control API",
            stderrOf("--config-dir", configDir.toString(), "ping"));
        assertAscii("the diagnostic for an unknown flag",
            stderrOf("--config-dir", configDir.toString(), "ping", "--nope"));
        assertAscii("the diagnostic for an unknown command",
            stderrOf("--config-dir", configDir.toString(), "nosuchgroup"));
        assertAscii("the version line", stdoutOf("--version"));
    }

    /**
     * A pane's text is arbitrary Unicode and the CLI is only its courier. The stream {@code main}
     * hands to {@link KorttyCli#run} is UTF-8 for exactly this reason; here the same stream shape is
     * built explicitly, so the assertion is about what {@code run} writes rather than about the
     * console the test happens to run on.
     */
    @Test
    void textThatOnlyPassesThroughTheClientKeepsItsNonAsciiCharacters() throws IOException {
        String awkward = "Grüße — ✅ 日本語";
        try (StubControlServer server = StubControlServer.onLoopback(StubControlServer.TOKEN)) {
            server.publishEndpoint(configDir);
            server.replyWith(new com.google.gson.JsonPrimitive(awkward));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

            int code = KorttyCli.run(new String[] {"--config-dir", configDir.toString(),
                "--raw", "ping"}, out, err);

            assertThat(code).isEqualTo(KorttyCli.EXIT_OK);
            assertWithMessage("a pane's own text must reach the caller unaltered; replacing it with"
                    + " '?' would corrupt the content the call asked for")
                .that(bytes.toString(StandardCharsets.UTF_8)).contains(awkward);
        }
    }

    private static void assertAscii(String what, String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            assertWithMessage("%s must be plain ASCII so a legacy console code page can render it,"
                    + " but it carries U+%s ('%s') at offset %s",
                    what, String.format("%04X", (int) character), character, index)
                .that((int) character).isLessThan(128);
        }
    }

    private String stderrOf(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        KorttyCli.run(args, out, err);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String stdoutOf(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        KorttyCli.run(args, out, err);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
