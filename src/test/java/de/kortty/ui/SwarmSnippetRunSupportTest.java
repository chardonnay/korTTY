package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.core.swarm.SwarmSnippetExecutor;
import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmSnippetRunSupportTest {

    private static final SwarmSnippetRunSupport.OutcomeLabels LABELS =
        new SwarmSnippetRunSupport.OutcomeLabels(
            "Cancelled", "Timeout", "Not connected", "Skipped: not POSIX", "Execution failed: {0}");

    private static SwarmSnippetExecutor.TargetOutcome outcome(
        String name, SwarmSnippetExecutor.OutcomeKind kind, int exitCode, String output, String error) {
        return new SwarmSnippetExecutor.TargetOutcome(name, name, kind, exitCode, output, error, 3L);
    }

    @Test
    void preparesCommandWithVariablesAndArguments() throws Exception {
        Path dir = Files.createTempDirectory("kortty-swarm-snippet-support");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            SnippetVariableManager variableManager = new SnippetVariableManager(dir);
            Snippet snippet = new Snippet("Check disk", "echo ${target} \"$1\"", "bash");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);
            variableManager.addOrUpdate("target", "/srv");

            SwarmSnippetRunSupport.PreparedRun prepared =
                new SwarmSnippetRunSupport(snippetManager, variableManager)
                    .prepare(snippet, List.of("alpha beta", "--force"));

            assertThat(prepared.command()).contains("base64 -d");
            assertThat(prepared.command()).contains("bash -s -- 'alpha beta' '--force'");
            assertThat(prepared.snippetName()).isEqualTo("Check disk");
            assertThat(prepared.arguments()).containsExactly("alpha beta", "--force").inOrder();
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blocksWhenVariableIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("kortty-swarm-snippet-missing-var");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Needs variable", "echo ${target}", "bash");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);

            try {
                new SwarmSnippetRunSupport(snippetManager, new SnippetVariableManager(dir))
                    .prepare(snippet, List.of());
                throw new AssertionError("expected SnippetRunBlockedException");
            } catch (SwarmSnippetRunSupport.SnippetRunBlockedException e) {
                assertThat(e.messageKey()).isEqualTo("ai.swarm.script.error.variable");
                assertThat(e.args()[0]).isEqualTo("${target}");
            }
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blocksUnsupportedLanguageWithTheOneLinerKey() throws Exception {
        Path dir = Files.createTempDirectory("kortty-swarm-snippet-lang");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Plain text", "just notes", "plain");
            snippet.setId("snippet-1");

            try {
                new SwarmSnippetRunSupport(snippetManager, new SnippetVariableManager(dir))
                    .prepare(snippet, List.of());
                throw new AssertionError("expected SnippetRunBlockedException");
            } catch (SwarmSnippetRunSupport.SnippetRunBlockedException e) {
                assertThat(e.messageKey()).isEqualTo("snippets.oneliner.notSupported");
            }
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void parseArgumentLinesTrimsAndDropsBlanks() {
        assertThat(SwarmSnippetRunSupport.parseArgumentLines(" a \n\n b c \n"))
            .containsExactly("a", "b c").inOrder();
        assertThat(SwarmSnippetRunSupport.parseArgumentLines(null)).isEmpty();
        assertThat(SwarmSnippetRunSupport.parseArgumentLines("  \n \n")).isEmpty();
    }

    @Test
    void buildsMarkdownTableWithEscapedCellsInRunOrder() {
        String markdown = SwarmSnippetRunSupport.buildResultMarkdown(
            "Script result: check",
            List.of("Server", "Exit code", "Output"),
            List.of(
                outcome("srv-1", SwarmSnippetExecutor.OutcomeKind.COMPLETED, 0, "line1\nline2 | pipe", null),
                outcome("srv-2", SwarmSnippetExecutor.OutcomeKind.COMPLETED, 3, "boom", null)),
            LABELS, SwarmSnippetRunSupport.DEFAULT_OUTPUT_CAP);

        assertThat(markdown).contains("**Script result: check**");
        assertThat(markdown).contains("| Server | Exit code | Output |");
        int first = markdown.indexOf("| srv-1 | 0 | line1<br>line2 \\| pipe |");
        int second = markdown.indexOf("| srv-2 | 3 | boom |");
        assertThat(first).isGreaterThan(-1);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void capsPerServerOutput() {
        String longOutput = "x".repeat(50);
        String markdown = SwarmSnippetRunSupport.buildResultMarkdown(
            null, List.of("Server", "Exit", "Output"),
            List.of(outcome("srv-1", SwarmSnippetExecutor.OutcomeKind.COMPLETED, 0, longOutput, null)),
            LABELS, 10);
        assertThat(markdown).contains("xxxxxxxxxx…");
        assertThat(markdown).doesNotContain(longOutput);
    }

    @Test
    void mapsOutcomeKindsToCells() {
        String markdown = SwarmSnippetRunSupport.buildResultMarkdown(
            null, List.of("Server", "Exit", "Output"),
            List.of(
                outcome("a", SwarmSnippetExecutor.OutcomeKind.CANCELLED, -1, "partial", null),
                outcome("b", SwarmSnippetExecutor.OutcomeKind.TIMED_OUT, -1, "slow", null),
                outcome("c", SwarmSnippetExecutor.OutcomeKind.NOT_CONNECTED, -1, "", null),
                outcome("d", SwarmSnippetExecutor.OutcomeKind.UNSUPPORTED_SHELL, -1, "", null),
                outcome("e", SwarmSnippetExecutor.OutcomeKind.ERROR, -1, "", "kaputt")),
            LABELS, 100);

        assertThat(markdown).contains("| a | Cancelled | partial |");
        assertThat(markdown).contains("| b | Timeout | slow |");
        assertThat(markdown).contains("| c | — | Not connected |");
        assertThat(markdown).contains("| d | — | Skipped: not POSIX |");
        assertThat(markdown).contains("| e | — | Execution failed: kaputt |");
    }
}
