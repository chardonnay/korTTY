package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BiFunction;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * How a highlight rule's snippet is made ready to type: as Send to Terminal would — the stored values of
 * its variables, its banner, a one-liner where its language allows — or why it cannot run: it no longer
 * exists, a declared variable has no stored value (a rule cannot ask), or nothing is left to type. Also the
 * texts the confirmation and the pane show, whose numbers come from the guard.
 */
class HighlightSnippetTriggerTest {

    /** English-like translations that show which key and arguments were used. */
    private static final BiFunction<String, Object[], String> I18N =
        (key, args) -> key + Arrays.toString(args);

    private Path dir;

    private SnippetManager manager;

    private SnippetVariableManager variables;

    @BeforeMethod
    void setUp() throws IOException {
        dir = Files.createTempDirectory("kortty-snippet-trigger");
        manager = new SnippetManager(dir);
        variables = new SnippetVariableManager(dir);
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }

    private Snippet snippet(String name, String content, String language) {
        Snippet snippet = new Snippet(name, content, language);
        snippet.setId("snippet-" + name);
        manager.addSnippet(snippet);
        return snippet;
    }

    @Test
    void aShellSnippetBecomesTheBannerAndAGeneratedOneLiner() {
        Snippet restart = snippet("Restart web", "systemctl restart nginx\nsystemctl status nginx", "bash");

        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(restart, "Server errors", manager, variables, I18N);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.problemKey()).isNull();
        assertWithMessage("an SSH session gets it without the echo of its base64 text")
            .that(preparation.generatedOneLiner()).isTrue();
        assertThat(preparation.line()).contains("base64");
        assertThat(preparation.line()).contains(HighlightSnippetTrigger.BANNER_KEY + "[Restart web]");
        assertWithMessage("one line, so one command reaches the shell")
            .that(preparation.line()).doesNotContain("\n");
    }

    @Test
    void storedVariablesAreFilledInAndOtherLanguagesAreTypedAsTheyAre() {
        variables.addOrUpdate("service", "nginx");
        Snippet query = snippet("Status", "SHOW STATUS LIKE '${service}%';", "sql");

        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(query, "Slow queries", manager, variables, I18N);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.generatedOneLiner()).isFalse();
        assertThat(preparation.line()).isEqualTo("SHOW STATUS LIKE 'nginx%';");
    }

    @Test
    void aDeclaredVariableWithoutAStoredValueKeepsTheSnippetFromRunning() {
        variables.addOrUpdate("target", ""); // declared, but asked for at every use
        Snippet clean = snippet("Clean", "rm -rf \"${target}/tmp\"", "bash");

        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(clean, "Disk full", manager, variables, I18N);

        assertThat(preparation.ready()).isFalse();
        assertThat(preparation.line()).isNull();
        assertThat(preparation.problemKey()).isEqualTo(HighlightSnippetTrigger.NEEDS_VALUE_KEY);
        assertThat(preparation.problem()).isEqualTo(HighlightSnippetTrigger.NEEDS_VALUE_KEY + "[Clean, ${target}]");
    }

    @Test
    void aVariableKorttyDoesNotKnowIsLeftToTheShellAsSendToTerminalDoes() {
        Snippet echo = snippet("Echo", "echo ${HOSTNAME_SUFFIX}", "text");

        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(echo, "x", manager, variables, I18N);

        assertThat(preparation.line()).isEqualTo("echo ${HOSTNAME_SUFFIX}");
    }

    @Test
    void aSnippetThatNoLongerExistsIsNamedByItsRule() {
        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(null, "Disk full", manager, variables, I18N);

        assertThat(preparation.ready()).isFalse();
        assertThat(preparation.problemKey()).isEqualTo(HighlightSnippetTrigger.MISSING_KEY);
        assertThat(preparation.problem()).isEqualTo(HighlightSnippetTrigger.MISSING_KEY + "[Disk full]");
        assertWithMessage("without a library nothing can run either")
            .that(HighlightSnippetTrigger.prepare(snippet("A", "ls", "bash"), "r", null, variables, I18N).problemKey())
            .isEqualTo(HighlightSnippetTrigger.MISSING_KEY);
    }

    @Test
    void anEmptySnippetCannotRun() {
        Snippet empty = snippet("Empty", "   \n  ", "bash");

        HighlightSnippetTrigger.Preparation preparation =
            HighlightSnippetTrigger.prepare(empty, "r", manager, variables, I18N);

        assertThat(preparation.problemKey()).isEqualTo(HighlightSnippetTrigger.UNSUPPORTED_KEY);
        assertThat(preparation.problem()).isEqualTo(HighlightSnippetTrigger.UNSUPPORTED_KEY + "[Empty]");
    }

    @Test
    void snippetNamesAreCleanedShortenedAndNeverEmpty() {
        Snippet hostile = new Snippet("\u001b]0;evil\u0007‮" + "N".repeat(200), "ls", "bash");
        String name = HighlightSnippetTrigger.snippetName(hostile, I18N);
        assertThat(name).doesNotContain("\u001b");
        assertThat(name).doesNotContain("‮");
        assertThat(name.length()).isAtMost(HighlightSnippetTrigger.MAX_NAME_CHARS);

        assertThat(HighlightSnippetTrigger.snippetName(new Snippet(" ", "ls", "bash"), I18N))
            .isEqualTo(HighlightSnippetTrigger.UNNAMED_SNIPPET_KEY + "[]");
    }

    @Test
    void theConfirmationShowsTheStartOfTheSnippet() {
        StringBuilder longScript = new StringBuilder();
        for (int line = 1; line <= 30; line++) {
            longScript.append("echo ").append(line).append("\r\n");
        }
        String preview = HighlightSnippetTrigger.preview(longScript.toString());

        assertThat(preview.split("\n")).hasLength(HighlightSnippetTrigger.PREVIEW_LINES + 1);
        assertThat(preview).startsWith("echo 1\necho 2\n");
        assertThat(preview).endsWith("\n…");
        assertThat(preview).doesNotContain("\r");
        assertThat(HighlightSnippetTrigger.preview("x".repeat(5_000)).length())
            .isAtMost(HighlightSnippetTrigger.PREVIEW_CHARS + 2);
        assertThat(HighlightSnippetTrigger.preview("ls -l")).isEqualTo("ls -l");
        assertThat(HighlightSnippetTrigger.preview(null)).isEmpty();
    }

    @Test
    void theRuleEditorListsSnippetsByNameWithTheirFolder() {
        Snippet zeta = new Snippet("zeta check", "ls", "bash");
        zeta.setId("z");
        Snippet alpha = new Snippet("Alpha restart", "ls", "bash");
        alpha.setId("a");
        alpha.setFolderId("ops");
        Snippet withoutId = new Snippet("no id", "ls", "bash");
        withoutId.setId(" ");

        java.util.List<HighlightRulesDialog.SnippetChoice> choices = HighlightRulesDialog.snippetChoices(
            java.util.Arrays.asList(zeta, null, alpha, withoutId),
            snippet -> "ops".equals(snippet.getFolderId()) ? "Ops/Web" : "");

        assertThat(choices).containsExactly(new HighlightRulesDialog.SnippetChoice("a", "Alpha restart (Ops/Web)"),
            new HighlightRulesDialog.SnippetChoice("z", "zeta check")).inOrder();
    }

    @Test
    void theTextsQuoteTheGuardsLimits() {
        assertThat(HighlightSnippetTrigger.confirmDetails(I18N))
            .isEqualTo(HighlightSnippetTrigger.CONFIRM_DETAILS_KEY + "[30, 3]");
        assertThat(HighlightSnippetTrigger.loopStoppedText(I18N))
            .isEqualTo(HighlightSnippetTrigger.LOOP_STOPPED_KEY + "[3, 2]");
    }
}
