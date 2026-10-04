package de.kortty.core.remote;

import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class RemoteShellTest {

    @DataProvider
    public Object[][] awkwardValues() {
        return new Object[][] {
            {"plain"},
            {""},
            {"with spaces  and\ttabs"},
            {"it's"},
            {"'"},
            {"''"},
            {"double \"quotes\""},
            {"line1\nline2\n"},
            {"$(touch /tmp/kortty-should-not-exist)"},
            {"`id`"},
            {"$HOME ${PATH} $1"},
            {"-rf"},
            {"--help"},
            {"*.txt ?"},
            {"; rm -rf / #"},
            {"back\\slash\\"},
            {"unicode äöü 中文 😀"},
        };
    }

    @Test(dataProvider = "awkwardValues")
    public void quoteProducesOneSingleQuotedWord(String value) {
        String quoted = RemoteShell.quote(value);

        assertThat(quoted).startsWith("'");
        assertThat(quoted).endsWith("'");
        assertThat(quoted).isEqualTo("'" + value.replace("'", "'\\''") + "'");
    }

    @Test(dataProvider = "awkwardValues")
    public void quotedValuesSurviveARealShellUnchanged(String value) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("needs a POSIX sh");
        }
        // sh -c 'printf %s "$1"' _ <quoted> — the quoted word is parsed by a real shell
        assertThat(throughShell("printf %s \"$1\"", RemoteShell.quote(value))).isEqualTo(value);
    }

    @Test
    public void joinQuotesEveryArgument() throws Exception {
        List<String> args = List.of("printf", "%s|", "a b", "it's", "$(x)");

        assertThat(RemoteShell.join(args)).isEqualTo("'printf' '%s|' 'a b' 'it'\\''s' '$(x)'");
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertThat(run(RemoteShell.join(args))).isEqualTo("a b|it's|$(x)|");
        }
    }

    @Test
    public void nulIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RemoteShell.quote("a\0b"));
        assertThrows(NullPointerException.class, () -> RemoteShell.quote(null));
    }

    private static String throughShell(String script, String quotedArgument) throws Exception {
        return run("sh -c " + RemoteShell.quote(script) + " _ " + quotedArgument);
    }

    private static String run(String commandLine) throws IOException, InterruptedException {
        // The script goes in on stdin as UTF-8, so the JVM's argv encoding (ASCII under a POSIX
        // locale in CI containers) cannot mangle non-ASCII test values.
        Process process = new ProcessBuilder("/bin/sh").redirectErrorStream(true).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write((commandLine + "\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(0);
        return new String(out, StandardCharsets.UTF_8);
    }
}
