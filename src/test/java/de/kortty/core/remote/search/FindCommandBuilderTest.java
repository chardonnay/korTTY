package de.kortty.core.remote.search;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class FindCommandBuilderTest {

    @Test
    public void refusesARootThatIsNotAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("home/x", "*", 10, true));
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("-delete", "*", 10, true));
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("~/x", "*", 10, true));
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("", "*", 10, true));
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("/a\0b", "*", 10, true));
        assertThrows(IllegalArgumentException.class, () -> FindCommandBuilder.build("/a", "*", 0, true));
    }

    @Test
    public void buildsTheCommandWithQuotedRootAndPattern() {
        assertThat(FindCommandBuilder.build("/var/log/", "*.log", 10, true))
            .isEqualTo("find -P '/var/log' -maxdepth 10 -xdev \\( -iname '*.log' \\) -print0 2>/dev/null");
        assertThat(FindCommandBuilder.build("/", "*a*", 3, false))
            .isEqualTo("find -P '/' -maxdepth 3 \\( -iname '*a*' \\) -print0 2>/dev/null");
    }

    @Test
    public void neverPipesIntoHead() {
        String command = FindCommandBuilder.build("/srv", "*x*", 10, true);
        assertThat(command).doesNotContain("head");
        assertThat(command).doesNotContain("|");
    }

    @Test
    public void injectionAttemptsStayLiteral() {
        String root = "/tmp/a'; rm -rf / #";
        String command = FindCommandBuilder.build(root, FindCommandBuilder.namePattern("$(reboot)`id`'x", false), 10, true);
        assertThat(command).startsWith("find -P '/tmp/a'\\''; rm -rf / #' -maxdepth 10");
        assertThat(command).contains("-iname '*$(reboot)`id`'\\''x*'");
    }

    @Test
    public void substringTextsBecomeEscapedWildcards() {
        assertThat(FindCommandBuilder.namePattern(" report ", false)).isEqualTo("*report*");
        assertThat(FindCommandBuilder.namePattern("a[b", false)).isEqualTo("*a\\[b*");
        assertThat(FindCommandBuilder.namePattern("x\\y", false)).isEqualTo("*x\\\\y*");
    }

    @Test
    public void globsKeepTheirSyntaxAndBracesWiden() {
        assertThat(FindCommandBuilder.namePattern("*.log", true)).isEqualTo("*.log");
        assertThat(FindCommandBuilder.namePattern("file?.txt", true)).isEqualTo("file?.txt");
        assertThat(FindCommandBuilder.namePattern("*.{py,sh}", true)).isEqualTo("*.*");
        assertThat(FindCommandBuilder.namePattern("[^a]*", true)).isEqualTo("[!a]*");
        assertThat(FindCommandBuilder.namePattern("[]x]?", true)).isEqualTo("[]x]?");
    }

    @Test
    public void theCommandRunsUnderARealShellWithItsArgumentsIntact() throws Exception {
        if (!Files.isExecutable(Path.of("/bin/sh")) || !Files.isExecutable(Path.of("/usr/bin/find"))) {
            throw new org.testng.SkipException("needs /bin/sh and /usr/bin/find");
        }
        Path dir = Files.createTempDirectory("kortty find '$(x)");
        try {
            Files.writeString(dir.resolve("Hit $(id).LOG"), "x");
            Files.writeString(dir.resolve("other.txt"), "x");
            String command = FindCommandBuilder.build(dir.toString(),
                FindCommandBuilder.namePattern("*.log", true), 10, true);
            Process process = new ProcessBuilder("/bin/sh", "-c", command).start();
            String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor();
            assertThat(out).isEqualTo(dir + "/Hit $(id).LOG\0");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
