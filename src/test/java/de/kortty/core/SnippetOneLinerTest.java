package de.kortty.core;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;


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
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("echo start; echo mid");
    }

    @Test
    void compactShellMergesBackslashContinuation() {
        String script = """
                echo hello \\
                world
                """;
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "shell");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("echo hello world");
    }

    @Test
    void compactShellStripsInlineComment() {
        String script = "echo hi  # trailing\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("echo hi");
    }

    @Test
    void compactShellKeepsHashInsideSingleQuotes() {
        String script = "echo '# not a comment'\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("echo '# not a comment'");
    }

    @Test
    void stripCommentsHashLangLinePreservesLiteralBackslashesInsideSingleQuotes() {
        String line = "puts 'path\\temp#still literal' # trailing";

        assertThat(SnippetOneLiner.stripCommentsHashLangLine(line)).isEqualTo("puts 'path\\temp#still literal'");
    }

    @Test
    void compactPythonStripsInlineComment() {
        String script = "x = 1  # init\nprint(x)\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("x = 1; print(x)");
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
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("echo a | cat; true && echo ok");
    }

    @Test
    void compactShellKeepsIfThenElseSyntaxValid() {
        String script = """
                if [ -f file ]; then
                  echo ok
                else
                  echo missing
                fi
                """;

        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");

        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("if [ -f file ]; then echo ok; else echo missing; fi");
    }

    @Test
    void compactShellKeepsLoopDoDoneSyntaxValid() {
        String script = """
                for file in *.log; do
                  echo "$file"
                done
                """;

        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "bash");

        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("for file in *.log; do echo \"$file\"; done");
    }

    @Test
    void embeddedRoundTripUtf8() {
        String original = "echo 'h\u00e9llo'\n# c\n";
        String expectedCleaned = "echo 'h\u00e9llo'";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(original, "python");
        assertThat(r.isOk()).isTrue();
        String line = r.line();
        assertThat(line.contains("\n") || line.contains("\r")).isFalse();
        assertThat(line.startsWith("echo '")).isTrue();
        assertThat(line.contains("' | base64 -d | python3")).isTrue();
        int a = line.indexOf('\'');
        int b = line.indexOf('\'', a + 1);
        String b64 = line.substring(a + 1, b);
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(expectedCleaned);
    }

    @Test
    void embeddedUsesBashForShellLanguage() {
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded("x=1\n", "shell");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line().endsWith("| bash")).isTrue();
    }

    @Test
    void embeddedBashPassesArgumentsAsEscapedArgvValues() {
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(
                "printf '<%s>\\n' \"$1\" \"$2\" \"$3\"\n",
                "bash",
                List.of("hello world", "a;b", "it's ok"));

        assertThat(r.isOk()).isTrue();
        assertThat(r.line().endsWith("| bash -s -- 'hello world' 'a;b' 'it'\\''s ok'")).isTrue();
    }

    @Test
    void embeddedInterpreterCommandsUseDashWhenArgumentsArePresent() {
        assertThat(SnippetOneLiner.toEmbedded("print('ok')\n", "python", List.of("one"))
                .line().endsWith("| python3 - 'one'")).isTrue();
        assertThat(SnippetOneLiner.toEmbedded("print qq(ok\\n);\n", "perl", List.of("one"))
                .line().endsWith("| perl - 'one'")).isTrue();
        assertThat(SnippetOneLiner.toEmbedded("puts 'ok'\n", "ruby", List.of("one"))
                .line().endsWith("| ruby - 'one'")).isTrue();
    }

    @Test
    void embeddedLargeScriptUsesHeredocToAvoidShellArgvLimit() {
        String big = "echo x\n".repeat(6_000);
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(big, "bash");
        assertThat(r.isOk()).isTrue();
        String out = r.line();
        assertWithMessage("large payload should use heredoc, not echo …").that(out.contains("<<'")).isTrue();
        assertThat(out.contains(SnippetOneLiner.EMBEDDED_HEREDOC_DELIM)).isTrue();
        assertThat(out.endsWith(SnippetOneLiner.EMBEDDED_HEREDOC_DELIM + "\n")).isTrue();
    }

    @Test
    void embeddedLargeScriptHeredocKeepsArgumentsOnInterpreterCommand() {
        String big = "echo \"$1\"\n".repeat(6_000);
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toEmbedded(big, "bash", List.of("hello world"));
        assertThat(r.isOk()).isTrue();
        String out = r.line();
        assertThat(out.startsWith("base64 -d <<'" + SnippetOneLiner.EMBEDDED_HEREDOC_DELIM
                + "' | bash -s -- 'hello world'\n")).isTrue();
        assertThat(out.endsWith(SnippetOneLiner.EMBEDDED_HEREDOC_DELIM + "\n")).isTrue();
    }

    @Test
    void compactPythonJoinsSimpleStatements() {
        String script = "a = 1\nprint(a)\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");
        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).isEqualTo("a = 1; print(a)");
    }

    @Test
    void compactPythonUsesExecWrapperForBlocks() {
        String script = "def f():\n    return 1\n";
        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "python");

        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).startsWith("python3 -c 'exec(");
        assertThat(r.line()).contains("def f():\\n    return 1");
    }

    @Test
    void compactPerlUsesEvalWrapperForSubroutines() {
        String script = """
                sub main {
                  print "ok\\n";
                }
                main();
                """;

        SnippetOneLiner.OneLinerResult r = SnippetOneLiner.toCompact(script, "perl");

        assertThat(r.isOk()).isTrue();
        assertThat(r.line()).startsWith("perl -e 'eval ");
        assertThat(r.line()).contains("sub main {\\n  print");
    }

    @Test
    void shellEscapeSingleQuotedEscapesQuotes() {
        assertThat(SnippetOneLiner.shellEscapeSingleQuoted("foo'bar")).isEqualTo("foo'\\''bar");
    }

    @Test
    void terminalStderrBannerShellPrefixEscapesMessage() {
        assertThat(SnippetOneLiner.terminalStderrBannerShellPrefix("a'b")).isEqualTo("printf '%s\\n' 'a'\\''b' >&2");
    }

    @Test
    void logicalLinesMergeContinuation() {
        List<String> lines = SnippetOneLiner.logicalLinesAfterMerge("a \\\nb\nc", true);
        assertThat(lines).isEqualTo(List.of("a b", "c"));
    }
}
