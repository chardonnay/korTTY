package de.kortty.ui;

import de.kortty.core.SnippetOneLiner;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * What Send to Terminal types into the shell (the pure payload functions behind the snippet
 * library's Send to Terminal and Send to Terminal with Parameters). {@code SnippetOneLinerTest}
 * covers the one-liner forms themselves; this pins how the send wraps them.
 */
class SnippetTerminalSendTest {

    private static final String BANNER = "Running snippet: deploy";

    private static String prefix(String banner) {
        return SnippetOneLiner.terminalStderrBannerShellPrefix(banner);
    }

    @Test
    void embeddedLanguageGetsTheBannerThenTheBase64Pipe() {
        String script = "echo one\necho two\n";

        String payload = SnippetTerminalSend.buildOneLinerPayloadForTerminal(script, "bash", BANNER);

        // The embedded form drops the trailing newline before encoding.
        String b64 = Base64.getEncoder().encodeToString("echo one\necho two".getBytes(StandardCharsets.UTF_8));
        assertThat(payload).isEqualTo(
            "printf '%s\\n' 'Running snippet: deploy' >&2 && echo '" + b64 + "' | base64 -d | bash");
    }

    @Test
    void everyEmbeddedLanguageIsWrappedTheSameWay() {
        for (String language : List.of("bash", "shell", "Bash", "python", "perl", "ruby")) {
            String script = "line one\nline two\n";
            String payload = SnippetTerminalSend.buildOneLinerPayloadForTerminal(script, language, BANNER);
            assertThat(payload)
                .isEqualTo(prefix(BANNER) + " && " + SnippetOneLiner.toEmbedded(script, language).line());
        }
    }

    @Test
    void bannerQuotesAreEscapedForTheShell() {
        String banner = "Running snippet: it's";

        String payload = SnippetTerminalSend.buildOneLinerPayloadForTerminal("ls\n", "bash", banner);

        assertThat(payload).startsWith("printf '%s\\n' 'Running snippet: it'\\''s' >&2 && ");
    }

    @Test
    void otherLanguagesAreSentVerbatimWithoutABanner() {
        String text = "SELECT 1;\nSELECT 2;";

        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal(text, "sql", BANNER)).isSameInstanceAs(text);
        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal(text, null, BANNER)).isSameInstanceAs(text);
        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal(text, "plain", BANNER, null))
            .isSameInstanceAs(text);
    }

    @Test
    void argumentsAreRejectedForLanguagesWithoutAnEmbeddedOneLiner() {
        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal("SELECT 1;", "sql", BANNER, List.of("a")))
            .isNull();
    }

    @Test
    void argumentsArePassedToTheInterpreter() {
        String script = "import sys\nprint(sys.argv[1:])\n";
        List<String> arguments = List.of("first", "with space");

        String payload = SnippetTerminalSend.buildOneLinerPayloadForTerminal(script, "python", BANNER, arguments);

        assertThat(payload)
            .isEqualTo(prefix(BANNER) + " && " + SnippetOneLiner.toEmbedded(script, "python", arguments).line());
        assertThat(payload).contains("| base64 -d | python3 - 'first' ");
        assertThat(payload).endsWith("'with space'");
    }

    @Test
    void largeScriptsKeepTheHeredocAfterTheBanner() {
        String script = "echo " + "x".repeat(60_000) + "\n";

        String payload = SnippetTerminalSend.buildOneLinerPayloadForTerminal(script, "bash", BANNER);

        assertThat(payload)
            .isEqualTo(prefix(BANNER) + " && " + SnippetOneLiner.toEmbedded(script, "bash").line());
        assertThat(payload).startsWith(prefix(BANNER) + " && base64 -d <<'");
        assertThat(payload).endsWith("\n");
    }

    @Test
    void aScriptWithNothingToRunHasNoPayload() {
        String onlyComments = "# nothing to run\n   # still nothing\n";

        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal(onlyComments, "bash", BANNER)).isNull();
        assertThat(SnippetTerminalSend.buildOneLinerPayloadForTerminal(onlyComments, "bash", BANNER, List.of("a")))
            .isNull();
    }

    @Test
    void argumentLinesKeepTheirSpacingAndDropBlankLines() {
        assertThat(SnippetTerminalSend.parseArgumentLines(" a \r\n\n  \nb c\rd"))
            .containsExactly(" a ", "b c", "d")
            .inOrder();
    }

    @Test
    void noArgumentTextMeansNoArguments() {
        assertThat(SnippetTerminalSend.parseArgumentLines(null)).isEmpty();
        assertThat(SnippetTerminalSend.parseArgumentLines("")).isEmpty();
        assertThat(SnippetTerminalSend.parseArgumentLines(" \n\t\n")).isEmpty();
    }
}
