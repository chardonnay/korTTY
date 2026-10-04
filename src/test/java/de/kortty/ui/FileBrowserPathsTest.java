package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.function.Predicate;

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
        // Built (not hardcoded) so the expectation matches this OS's own separator/root, the same
        // way HOME.resolve("Projects/korTTY") does above: a literal "/opt/tools" only round-trips
        // through Path.toAbsolutePath().normalize().toString() on POSIX.
        Path outside = HOME.resolveSibling("opt-tools");
        assertThat(FileBrowserPaths.abbreviateHome(outside, HOME))
            .isEqualTo(outside.toAbsolutePath().normalize().toString());
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
    void globFilterDetectionMatchesTheCompiledFilter() {
        assertThat(FileBrowserPaths.isGlobFilter("*.log")).isTrue();
        assertThat(FileBrowserPaths.isGlobFilter(" *.{py,sh} ")).isTrue();
        assertThat(FileBrowserPaths.isGlobFilter("report")).isFalse();
        assertThat(FileBrowserPaths.isGlobFilter("[abc")).isFalse();
        assertThat(FileBrowserPaths.isGlobFilter("[z-a]")).isFalse();
        assertThat(FileBrowserPaths.isGlobFilter("{a,{b}}")).isFalse();
        assertThat(FileBrowserPaths.isGlobFilter("")).isFalse();
        assertThat(FileBrowserPaths.isGlobFilter(null)).isFalse();
    }

    @Test
    void nameFilterStarMatchesTheWholeNameIgnoringCase() {
        Predicate<String> logs = FileBrowserPaths.compileNameFilter("*.log");
        assertThat(logs.test("app.log")).isTrue();
        assertThat(logs.test("APP.LOG")).isTrue();
        assertThat(logs.test(".log")).isTrue();
        // A glob matches the whole name, not a part of it.
        assertThat(logs.test("log.txt")).isFalse();
        assertThat(logs.test("app.log.gz")).isFalse();

        Predicate<String> backups = FileBrowserPaths.compileNameFilter("backup*");
        assertThat(backups.test("backup-2026.tar")).isTrue();
        assertThat(backups.test("Backup")).isTrue();
        assertThat(backups.test("old-backup")).isFalse();
    }

    @Test
    void nameFilterSupportsQuestionMarkClassesAndAlternatives() {
        Predicate<String> scripts = FileBrowserPaths.compileNameFilter("*.{py,sh}");
        assertThat(scripts.test("deploy.sh")).isTrue();
        assertThat(scripts.test("tool.PY")).isTrue();
        assertThat(scripts.test("notes.txt")).isFalse();

        Predicate<String> numbered = FileBrowserPaths.compileNameFilter("file?.txt");
        assertThat(numbered.test("file1.txt")).isTrue();
        assertThat(numbered.test("file.txt")).isFalse();
        assertThat(numbered.test("file12.txt")).isFalse();

        Predicate<String> aOrB = FileBrowserPaths.compileNameFilter("[ab]*");
        assertThat(aOrB.test("alpha")).isTrue();
        assertThat(aOrB.test("Beta")).isTrue();
        assertThat(aOrB.test("gamma")).isFalse();

        Predicate<String> notA = FileBrowserPaths.compileNameFilter("[!a]*");
        assertThat(notA.test("beta")).isTrue();
        assertThat(notA.test("alpha")).isFalse();
        assertThat(FileBrowserPaths.compileNameFilter("[^a]*").test("alpha")).isFalse();

        Predicate<String> digits = FileBrowserPaths.compileNameFilter("data[0-9].csv");
        assertThat(digits.test("data7.csv")).isTrue();
        assertThat(digits.test("datax.csv")).isFalse();
    }

    @Test
    void nameFilterQuotesRegexMetacharacters() {
        Predicate<String> filter = FileBrowserPaths.compileNameFilter("a+b(1)*");
        assertThat(filter.test("a+b(1).txt")).isTrue();
        assertThat(filter.test("aab1.txt")).isFalse();
        assertThat(FileBrowserPaths.compileNameFilter("$HOME.*").test("$HOME.bak")).isTrue();
        assertThat(FileBrowserPaths.compileNameFilter("C:\\temp*").test("C:\\temp\\x")).isTrue();
        // Inside a class, '-' at an edge and '\' are plain characters.
        Predicate<String> dashOrBackslash = FileBrowserPaths.compileNameFilter("x[-\\]y");
        assertThat(dashOrBackslash.test("x-y")).isTrue();
        assertThat(dashOrBackslash.test("x\\y")).isTrue();
        assertThat(dashOrBackslash.test("xay")).isFalse();
    }

    @Test
    void invalidGlobFallsBackToSubstring() {
        Predicate<String> unclosed = FileBrowserPaths.compileNameFilter("[abc");
        assertThat(unclosed.test("x[abc]y.txt")).isTrue();
        assertThat(unclosed.test("abc.txt")).isFalse();

        assertThat(FileBrowserPaths.compileNameFilter("{a,b").test("my{a,b.txt")).isTrue();
        assertThat(FileBrowserPaths.compileNameFilter("[z-a]").test("[Z-A]")).isTrue();
        assertThat(FileBrowserPaths.compileNameFilter("[z-a]").test("z")).isFalse();
    }

    @Test
    void plainNameFilterKeepsSubstringSemantics() {
        Predicate<String> plain = FileBrowserPaths.compileNameFilter(" Rep ");
        assertThat(plain.test("Report.TXT")).isTrue();
        assertThat(plain.test("my-report")).isTrue();
        assertThat(plain.test("notes.txt")).isFalse();
        assertThat(plain.test(null)).isFalse();
        // A lone '}' or ',' is no glob.
        assertThat(FileBrowserPaths.compileNameFilter("a,b").test("x a,b y")).isTrue();

        assertThat(FileBrowserPaths.compileNameFilter("").test("anything")).isTrue();
        assertThat(FileBrowserPaths.compileNameFilter("   ").test("anything")).isTrue();
        assertThat(FileBrowserPaths.compileNameFilter(null).test("anything")).isTrue();
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
