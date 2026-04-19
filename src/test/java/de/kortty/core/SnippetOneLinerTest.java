package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetOneLinerTest {

    @Test
    void compactShellJoinsWithSemicolonsAndStripsFullLineComments() {
        String script = """
                # setup
                echo start
                echo mid
                # tail comment
                """;
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertTrue(r.isOk());
        assertEquals("echo start; echo mid", r.line());
    }

    @Test
    void compactShellMergesBackslashContinuation() {
        String script = """
                echo hello \\
                world
                """;
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "shell");
        assertTrue(r.isOk());
        assertEquals("echo hello world", r.line());
    }

    @Test
    void compactShellStripsInlineComment() {
        String script = "echo hi  # trailing\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertTrue(r.isOk());
        assertEquals("echo hi", r.line());
    }

    @Test
    void compactShellKeepsHashInsideSingleQuotes() {
        String script = "echo '# not a comment'\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertTrue(r.isOk());
        assertEquals("echo '# not a comment'", r.line());
    }

    @Test
    void stripCommentsHashLangLinePreservesLiteralBackslashesInsideSingleQuotes() {
        String line = "puts 'path\\temp#still literal' # trailing";

        assertEquals("puts 'path\\temp#still literal'", SnippetOneLiner.stripCommentsHashLangLine(line));
    }

    @Test
    void compactPythonStripsInlineComment() {
        String script = "x = 1  # init\nprint(x)\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");
        assertTrue(r.isOk());
        assertEquals("x = 1; print(x)", r.line());
    }

    @Test
    void compactShellUsesSpaceAfterPipeOrAnd() {
        String script = """
                echo a |
                cat
                true &&
                echo ok
                """;
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertTrue(r.isOk());
        assertEquals("echo a | cat; true && echo ok", r.line());
    }

    @Test
    void embeddedRoundTripUtf8() {
        String original = "echo 'h\u00e9llo'\n# c\n";
        String expectedCleaned = "echo 'h\u00e9llo'";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(original, "python");
        assertTrue(r.isOk());
        String line = r.line();
        assertFalse(line.contains("\n") || line.contains("\r"));
        assertTrue(line.startsWith("echo '"));
        assertTrue(line.contains("' | base64 -d | python3"));
        int a = line.indexOf('\'');
        int b = line.indexOf('\'', a + 1);
        String b64 = line.substring(a + 1, b);
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertEquals(expectedCleaned, new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void embeddedUsesBashForShellLanguage() {
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded("x=1\n", "shell");
        assertTrue(r.isOk());
        assertTrue(r.line().endsWith("| bash"));
    }

    @Test
    void embeddedLargeScriptUsesHeredocToAvoidShellArgvLimit() {
        String big = "echo x\n".repeat(6_000);
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(big, "bash");
        assertTrue(r.isOk());
        String out = r.line();
        assertTrue(out.contains("<<'"), "large payload should use heredoc, not echo …");
        assertTrue(out.contains(SnippetOneLiner.EMBEDDED_HEREDOC_DELIM));
        assertTrue(out.endsWith(SnippetOneLiner.EMBEDDED_HEREDOC_DELIM + "\n"));
    }

    @Test
    void compactPythonJoinsSimpleStatements() {
        String script = "a = 1\nprint(a)\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");
        assertTrue(r.isOk());
        assertEquals("a = 1; print(a)", r.line());
    }

    @Test
    void compactPythonFailsOnDef() {
        String script = "def f():\n    return 1\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");
        assertFalse(r.isOk());
        assertEquals("snippets.oneliner.compact.blocks", r.errorKey());
    }

    @Test
    void shellEscapeSingleQuotedEscapesQuotes() {
        assertEquals("foo'\\''bar", SnippetOneLiner.shellEscapeSingleQuoted("foo'bar"));
    }

    @Test
    void terminalStderrBannerShellPrefixEscapesMessage() {
        assertEquals("printf '%s\\n' 'a'\\''b' >&2", SnippetOneLiner.terminalStderrBannerShellPrefix("a'b"));
    }

    @Test
    void logicalLinesMergeContinuation() {
        List<String> lines = SnippetOneLiner.logicalLinesAfterMerge("a \\\nb\nc", true);
        assertEquals(List.of("a b", "c"), lines);
    }
}
