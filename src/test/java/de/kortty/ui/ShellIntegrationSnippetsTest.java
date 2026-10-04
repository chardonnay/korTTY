package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.ShellIntegrationSnippet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * The shell snippets korTTY ships for shell integration ({@code src/main/resources/shell-integration})
 * and prints on the guide page: they mark prompts and commands with OSC 133 A/B/C/D, report the
 * directory with OSC 7, load once and only where they belong, and the guide shows exactly the
 * shipped text. Where the shell is installed, the snippets are also parsed and run.
 */
class ShellIntegrationSnippetsTest {

    private static final List<String> SNIPPETS = List.of("kortty.bash", "kortty.zsh", "kortty.fish");
    private static final Path GUIDE_PAGE = Path.of("app-docs/site/docs/en/features/shell-integration.md");
    private static final Pattern FENCE = Pattern.compile("(?ms)^```(bash|zsh|fish)\\n(.*?)\\n```$");
    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";

    @Test
    void everySnippetMarksPromptsAndCommandsAndReportsTheDirectory() throws IOException {
        for (String snippet : SNIPPETS) {
            String text = snippet(snippet);
            for (String mark : List.of("133;A", "133;B", "133;C", "133;D;")) {
                assertWithMessage(snippet + " sends " + mark).that(text).contains(mark);
            }
            assertWithMessage(snippet + " sends OSC 7").that(text).contains("]7;file://");
        }
    }

    @Test
    void everySnippetLoadsOnceAndOnlyInAnInteractiveTerminal() throws IOException {
        for (String snippet : SNIPPETS) {
            String text = snippet(snippet);
            assertWithMessage(snippet + " checks that it was not loaded yet").that(text).contains("__kortty_si_loaded");
            assertWithMessage(snippet + " stays out of TERM=dumb").that(text).contains("dumb");
        }
        assertThat(snippet("kortty.bash")).contains("[[ $- == *i* &&");
        assertThat(snippet("kortty.zsh")).contains("[[ -o interactive &&");
        assertThat(snippet("kortty.fish")).contains("if status is-interactive;");
    }

    @Test
    void theBashSnippetKeepsTheHooksTheUserHas() throws IOException {
        String bash = snippet("kortty.bash");
        assertWithMessage("D needs the command's $?, so the hook runs first")
            .that(bash).contains("PROMPT_COMMAND=(__kortty_si_precmd \"${PROMPT_COMMAND[@]}\" __kortty_si_prompt)");
        assertThat(bash).contains("PROMPT_COMMAND=__kortty_si_precmd${PROMPT_COMMAND:+$'\\n'$PROMPT_COMMAND}$'\\n'__kortty_si_prompt");
        assertWithMessage("bash 4.4 brought PS0").that(bash).contains("BASH_VERSINFO[1] >= 4");
        assertThat(bash.indexOf("local exit_code=$?")).isLessThan(bash.indexOf("printf '\\e]133;D;%s\\a' \"$exit_code\""));
        String precmd = bash.substring(bash.indexOf("__kortty_si_precmd() {"), bash.indexOf("__kortty_si_prompt() {"));
        assertWithMessage("the hooks after D still see the command's $?")
            .that(precmd).contains("return \"$exit_code\"");
    }

    @Test
    void theGuidePrintsEachSnippetAsShipped() throws IOException {
        String page = Files.readString(GUIDE_PAGE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> blocks = new ArrayList<>();
        Matcher matcher = FENCE.matcher(page);
        while (matcher.find()) {
            blocks.add(matcher.group(2));
        }
        for (String snippet : SNIPPETS) {
            assertWithMessage("the guide shows " + snippet + " verbatim")
                .that(blocks).contains(snippet(snippet).stripTrailing());
        }
    }

    @Test
    void theSetupWindowShowsAndCopiesEachSnippetAsTheGuidePrintsIt() throws IOException {
        String page = Files.readString(GUIDE_PAGE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> blocks = new ArrayList<>();
        Matcher matcher = FENCE.matcher(page);
        while (matcher.find()) {
            blocks.add(matcher.group(2));
        }
        List<String> shipped = new ArrayList<>();
        for (ShellIntegrationSnippet snippet : ShellIntegrationSnippet.values()) {
            String fileName = snippet.resource().substring("/shell-integration/".length());
            shipped.add(fileName);
            String text = snippet.text();
            assertWithMessage(snippet + " is the shipped resource").that(text).isEqualTo(snippet(fileName).stripTrailing() + "\n");
            assertWithMessage(snippet + " is what the guide prints").that(blocks).contains(text.stripTrailing());
            assertWithMessage(snippet + " has no carriage return, which the shell would run as part of a command")
                .that(text).doesNotContain("\r");
            assertWithMessage(snippet + "'s tab is named after its shell").that(snippet.shellName())
                .isEqualTo(snippet.name().toLowerCase(Locale.ROOT));
        }
        assertWithMessage("the window has a tab for every snippet korTTY ships").that(shipped).containsExactlyElementsIn(SNIPPETS);
    }

    @Test
    void theSetupWindowLinksToTheSectionWithTheSnippets() throws IOException {
        assertThat(ShellIntegrationSetupDialog.GUIDE_LOCATION).isEqualTo("features/shell-integration.html#setting-it-up");
        String page = Files.readString(GUIDE_PAGE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(page).contains("\n## Setting it up { #setting-it-up }\n");
        // The German heading is translated, so the anchor the window links to is spelled out on both.
        String germanPage = Files.readString(Path.of("app-docs/site/docs/de/features/shell-integration.md"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertWithMessage("the German page keeps the anchor").that(germanPage).containsMatch("\n## [^\n]+ \\{ #setting-it-up \\}\n");
    }

    @Test
    void theSnippetsParse() throws Exception {
        int checked = 0;
        checked += syntaxCheck("bash", "-n", "kortty.bash");
        checked += syntaxCheck("zsh", "-n", "kortty.zsh");
        checked += syntaxCheck("fish", "--no-execute", "kortty.fish");
        if (checked == 0) {
            throw new SkipException("No bash, zsh or fish to parse the snippets with");
        }
    }

    @Test
    void zshMarksTheCommandsItRuns() throws Exception {
        requireShell("zsh");
        Path dir = Files.createTempDirectory("kortty-zsh");
        try {
            Files.createDirectory(dir.resolve("a b%x"));
            String output = runInteractive(List.of("zsh", "-i"), dir,
                "cd 'a b%x'\nsource " + quoted(resource("kortty.zsh")) + "\nfalse\ntrue\nexit\n", "ZDOTDIR", dir.toString());
            assertThat(output).contains(ESC + "]133;C" + BEL + ESC + "]133;D;1" + BEL);
            assertThat(output).contains(ESC + "]133;C" + BEL + ESC + "]133;D;0" + BEL);
            assertThat(output).contains(ESC + "]7;file://");
            assertWithMessage("OSC 7 percent-encodes the directory byte by byte")
                .that(output).contains("/a%20b%25x" + BEL);
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    void bashMarksTheCommandsItRuns() throws Exception {
        int[] version = requireBash(4, 4);
        Path dir = Files.createTempDirectory("kortty-bash");
        try {
            Files.createDirectory(dir.resolve("a b%x"));
            // bash prints its prompts and PS0, which carries C, to stderr: read both streams, as a
            // terminal shows both.
            String output = runInteractive(List.of("bash", "--norc", "--noprofile", "-i"), dir, true,
                "cd 'a b%x'\nPROMPT_COMMAND='echo \"hook saw $?\"'\nsource " + quoted(resource("kortty.bash"))
                    + "\nfalse\nexit\n");
            assertThat(output).contains(ESC + "]133;C" + BEL);
            assertThat(output).contains(ESC + "]133;D;1" + BEL);
            assertWithMessage("OSC 7 percent-encodes the directory byte by byte")
                .that(output).contains("/a%20b%25x" + BEL);
            assertWithMessage("a PROMPT_COMMAND hook the user had still sees the command's exit status")
                .that(output).contains("hook saw 1\n");
            if (version[0] > 5 || (version[0] == 5 && version[1] >= 1)) {
                // bash 5.1 and later also take PROMPT_COMMAND as an array.
                String arrayOutput = runInteractive(List.of("bash", "--norc", "--noprofile", "-i"), dir, true,
                    "PROMPT_COMMAND=(true 'echo \"hook saw $?\"')\nsource " + quoted(resource("kortty.bash"))
                        + "\nfalse\nexit\n");
                assertThat(arrayOutput).contains(ESC + "]133;D;1" + BEL);
                assertThat(arrayOutput).contains("hook saw 1\n");
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    private static String snippet(String name) throws IOException {
        try (InputStream in = ShellIntegrationSnippetsTest.class.getResourceAsStream("/shell-integration/" + name)) {
            assertWithMessage("missing resource " + name).that(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static Path resource(String name) {
        return Path.of("src/main/resources/shell-integration", name).toAbsolutePath();
    }

    private static String quoted(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /** Parses {@code snippet} with {@code shell}; 0 when the shell is not installed. */
    private static int syntaxCheck(String shell, String flag, String snippet) throws Exception {
        if (!available(shell)) {
            return 0;
        }
        Process process = new ProcessBuilder(shell, flag, resource(snippet).toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertWithMessage(shell + " rejects " + snippet + ": " + output).that(process.exitValue()).isEqualTo(0);
        return 1;
    }

    private static String runInteractive(List<String> command, Path dir, String input, String... environment)
            throws Exception {
        return runInteractive(command, dir, false, input, environment);
    }

    /**
     * Runs {@code command} on {@code input} and returns what it printed: its stdout, and with
     * {@code withErrors} also its stderr, where an interactive bash writes its prompts and PS0.
     */
    private static String runInteractive(List<String> command, Path dir, boolean withErrors, String input,
            String... environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("HOME", dir.toString());
        for (int index = 0; index + 1 < environment.length; index += 2) {
            builder.environment().put(environment[index], environment[index + 1]);
        }
        if (withErrors) {
            builder.redirectErrorStream(true);
        } else {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        Process process = builder.start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return output;
    }

    private static void requireShell(String shell) {
        if (!available(shell)) {
            throw new SkipException(shell + " is not installed");
        }
    }

    /** The bash version, or a skip when bash is missing or older than {@code major.minor}. */
    private static int[] requireBash(int major, int minor) throws Exception {
        requireShell("bash");
        Process process = new ProcessBuilder("bash", "-c", "echo ${BASH_VERSINFO[0]} ${BASH_VERSINFO[1]}")
            .redirectErrorStream(true).start();
        String[] parts = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().split(" ");
        process.waitFor(30, TimeUnit.SECONDS);
        int[] version = {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        if (version[0] < major || (version[0] == major && version[1] < minor)) {
            throw new SkipException("bash " + version[0] + "." + version[1] + " is older than " + major + "." + minor);
        }
        return version;
    }

    /** Whether {@code shell} can be started here; never on Windows, where a bash on the PATH is a WSL or MSYS one. */
    private static boolean available(String shell) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(shell, "-c", "exit 0").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A temp directory left behind is harmless.
        }
    }
}
