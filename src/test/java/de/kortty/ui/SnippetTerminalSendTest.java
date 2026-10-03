package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetPlaceholderResolver;
import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * What Send to Terminal types into the shell (the pure payload functions behind the snippet
 * library's Send to Terminal and Send to Terminal with Parameters). {@code SnippetOneLinerTest}
 * covers the one-liner forms themselves; this pins how the send wraps them, and when a use is
 * counted relative to the caller's callback and the main-window lookup.
 */
class SnippetTerminalSendTest {

    private static final String BANNER = "Running snippet: deploy";

    private static String prefix(String banner) {
        return SnippetOneLiner.terminalStderrBannerShellPrefix(banner);
    }

    @Test
    void aCountedUseIsSavedBeforeTheCallerIsTold() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippet-terminal-send");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("List", "ls -la", "bash");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);
            List<Integer> savedCountsSeenByCaller = new ArrayList<>();
            SnippetTerminalSend send = new SnippetTerminalSend(
                snippetManager,
                () -> { throw new AssertionError("nothing is missing, so no dialog needs an owner"); },
                () -> savedCountsSeenByCaller.add(savedUsageCount(dir, "snippet-1")));

            SnippetPlaceholderResolver.ResolvedSnippet resolved = send.resolveAndPrompt(snippet);

            assertThat(resolved.text()).isEqualTo("ls -la");
            assertThat(snippet.getUsageCount()).isEqualTo(1);
            assertThat(savedCountsSeenByCaller).containsExactly(1);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void sendToTerminalCountsTheUseBeforeLookingForTheMainWindow() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippet-terminal-send-order");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Query", "SELECT 1;", "sql");
            snippetManager.addSnippet(snippet);
            List<String> calls = new ArrayList<>();
            SnippetTerminalSend send = new SnippetTerminalSend(
                snippetManager,
                () -> { throw new AssertionError("no dialog or alert expected"); },
                () -> calls.add("afterUsage"));

            send.sendToTerminal(snippet, () -> {
                calls.add("mainWindow");
                return null;
            });

            assertThat(calls).containsExactly("afterUsage", "mainWindow").inOrder();
            assertThat(snippet.getUsageCount()).isEqualTo(1);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aBlankSnippetIsCountedButNeverSent() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippet-terminal-send-blank");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Empty", "  \n", "bash");
            snippetManager.addSnippet(snippet);
            List<String> calls = new ArrayList<>();
            SnippetTerminalSend send = new SnippetTerminalSend(
                snippetManager,
                () -> { throw new AssertionError("no dialog or alert expected"); },
                () -> calls.add("afterUsage"));

            send.sendToTerminal(snippet, () -> {
                calls.add("mainWindow");
                return null;
            });

            assertThat(calls).containsExactly("afterUsage");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static int savedUsageCount(Path dir, String snippetId) {
        try {
            SnippetManager reloaded = new SnippetManager(dir);
            reloaded.load();
            return reloaded.getAllSnippets().stream()
                .filter(saved -> snippetId.equals(saved.getId()))
                .findFirst()
                .orElseThrow()
                .getUsageCount();
        } catch (Exception e) {
            throw new AssertionError("the snippets file could not be read back", e);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
