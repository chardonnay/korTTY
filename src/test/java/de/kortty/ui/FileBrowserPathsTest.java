package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class FileBrowserPathsTest {

    private static final Path HOME = Paths.get("/Users/tester");

    @Test
    void abbreviatesPathsInsideHome() {
        assertThat(FileBrowserPaths.abbreviateHome(HOME, HOME)).isEqualTo("~");
        assertThat(FileBrowserPaths.abbreviateHome(HOME.resolve("Projects/korTTY"), HOME))
            .isEqualTo("~/Projects/korTTY");
    }

    @Test
    void keepsPathsOutsideHomeAbsolute() {
        assertThat(FileBrowserPaths.abbreviateHome(Paths.get("/opt/tools"), HOME)).isEqualTo("/opt/tools");
        assertThat(FileBrowserPaths.abbreviateHome(null, HOME)).isEmpty();
    }

    @Test
    void abbreviatesRemotePaths() {
        assertThat(FileBrowserPaths.abbreviateRemote("/home/tester", "/home/tester")).isEqualTo("~");
        assertThat(FileBrowserPaths.abbreviateRemote("/home/tester/logs", "/home/tester")).isEqualTo("~/logs");
        assertThat(FileBrowserPaths.abbreviateRemote("/var/log", "/home/tester")).isEqualTo("/var/log");
        assertThat(FileBrowserPaths.abbreviateRemote("/home/testertwo", "/home/tester")).isEqualTo("/home/testertwo");
        assertThat(FileBrowserPaths.abbreviateRemote("/etc", "/")).isEqualTo("/etc");
    }

    @Test
    void expandsTildeAgainstHome() {
        assertThat(FileBrowserPaths.expandHome("~", HOME)).isEqualTo(HOME);
        assertThat(FileBrowserPaths.expandHome("  ~/Projects ", HOME)).isEqualTo(HOME.resolve("Projects"));
        assertThat(FileBrowserPaths.expandHome("/opt/tools", HOME)).isEqualTo(Paths.get("/opt/tools"));
        assertThat(FileBrowserPaths.expandHome("", HOME)).isEqualTo(HOME);
    }

    @Test
    void expandsRemoteTilde() {
        assertThat(FileBrowserPaths.expandRemoteHome("~", "/home/tester")).isEqualTo("/home/tester");
        assertThat(FileBrowserPaths.expandRemoteHome("~/logs", "/home/tester")).isEqualTo("/home/tester/logs");
        assertThat(FileBrowserPaths.expandRemoteHome("/var/log", "/home/tester")).isEqualTo("/var/log");
    }

    @Test
    void resolvesUniqueDestinationWithoutConflict() throws Exception {
        Path dir = Files.createTempDirectory("kortty-unique");
        try {
            assertThat(FileBrowserPaths.uniqueDestination(dir, "report.txt"))
                .isEqualTo(dir.resolve("report.txt"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void appendsCounterBeforeExtensionOnConflict() throws Exception {
        Path dir = Files.createTempDirectory("kortty-unique");
        try {
            Files.createFile(dir.resolve("report.txt"));
            Files.createFile(dir.resolve("report (2).txt"));
            assertThat(FileBrowserPaths.uniqueDestination(dir, "report.txt"))
                .isEqualTo(dir.resolve("report (3).txt"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void handlesExtensionlessAndDotfileConflicts() throws Exception {
        Path dir = Files.createTempDirectory("kortty-unique");
        try {
            Files.createFile(dir.resolve("Makefile"));
            Files.createFile(dir.resolve(".gitignore"));
            assertThat(FileBrowserPaths.uniqueDestination(dir, "Makefile"))
                .isEqualTo(dir.resolve("Makefile (2)"));
            assertThat(FileBrowserPaths.uniqueDestination(dir, ".gitignore"))
                .isEqualTo(dir.resolve(".gitignore (2)"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void matchesFilterCaseInsensitively() {
        assertThat(FileBrowserPaths.matchesFilter("Report.TXT", "rep")).isTrue();
        assertThat(FileBrowserPaths.matchesFilter("Report.TXT", " txt ")).isTrue();
        assertThat(FileBrowserPaths.matchesFilter("Report.TXT", "png")).isFalse();
        assertThat(FileBrowserPaths.matchesFilter("Report.TXT", "")).isTrue();
        assertThat(FileBrowserPaths.matchesFilter("Report.TXT", null)).isTrue();
        assertThat(FileBrowserPaths.matchesFilter(null, "x")).isFalse();
    }

    @Test
    void quotesForPosixShell() {
        assertThat(FileBrowserPaths.shellQuote("/tmp/plain")).isEqualTo("'/tmp/plain'");
        assertThat(FileBrowserPaths.shellQuote("My File.txt")).isEqualTo("'My File.txt'");
        assertThat(FileBrowserPaths.shellQuote("it's here")).isEqualTo("'it'\\''s here'");
        assertThat(FileBrowserPaths.shellQuote(null)).isEqualTo("''");
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
