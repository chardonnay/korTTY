package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.ShellIntegrationInjection.Plan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The wrapper files of {@link ShellIntegrationInjection} in the real shells: each parses
 * ({@code bash -n}, {@code zsh -n}, {@code fish --no-execute}, {@code sh -n}), and started the way
 * korTTY starts it, with a home folder of its own, the shell runs the user's startup files before
 * the snippet and leaves no trace of the wrapper behind. A shell that is not installed skips its test;
 * Windows skips them all, since its {@code bash} on the PATH may be WSL's.
 */
class ShellIntegrationWrapperShellsTest {

    private Path temp;

    private Path home;

    /** The wrapper folders' root, with a quote and a blank in its path. */
    private Path root;

    /** The wrapper folder of the last plan. */
    private ShellIntegrationWrapperDirectory wrapper;

    @BeforeMethod
    void setUp() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("The shells on a Windows PATH may be WSL's");
        }
        // A quote and a blank in the path prove the quoting of every file.
        temp = Files.createTempDirectory("kortty si test").toRealPath();
        home = Files.createDirectory(temp.resolve("home"));
        root = temp.resolve("o'brien wrappers");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (temp != null) {
            try (var walk = Files.walk(temp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test(timeOut = 60_000)
    void everyWrapperParses() throws Exception {
        wrapper = ShellIntegrationWrapperDirectory.create(root);
        Plan plan = ShellIntegrationInjection.planForHostLoginShell(Map.of(), wrapper.shellPath()).orElseThrow();
        wrapper.write(plan.files());
        Path dir = wrapper.path();
        boolean checked = false;
        if (available("bash")) {
            assertParses(List.of("bash", "-n", dir.resolve("bashrc").toString()));
            assertParses(List.of("bash", "-n", "-c", plan.command().get(2)));
            checked = true;
        }
        if (available("zsh")) {
            for (String name : List.of(".zshenv", ".zprofile", ".zshrc")) {
                assertParses(List.of("zsh", "-n", dir.resolve("zsh").resolve(name).toString()));
            }
            checked = true;
        }
        if (available("fish")) {
            assertParses(List.of("fish", "--no-execute", dir.resolve("kortty.fish").toString()));
            checked = true;
        }
        if (available("sh")) {
            assertParses(List.of("sh", "-n", "-c", plan.command().get(2)));
            checked = true;
        }
        if (!checked) {
            throw new SkipException("None of bash, zsh, fish and sh is installed");
        }
    }

    @Test(timeOut = 60_000)
    void aZshRunsTheUsersFilesThenTheSnippetAndPutsZdotdirBack() throws Exception {
        requireShell("zsh");
        Files.writeString(home.resolve(".zshenv"), "export MARK_ENV=env\n");
        Files.writeString(home.resolve(".zprofile"), "MARK_PROFILE=profile\n");
        Files.writeString(home.resolve(".zshrc"), "MARK_RC=rc\nuser_hook() { :; }\nprecmd_functions+=(user_hook)\n");
        Files.writeString(home.resolve(".zlogin"), "MARK_LOGIN=login\n");
        String probe = "print -r -- \"[env=${MARK_ENV-}|profile=${MARK_PROFILE-}|rc=${MARK_RC-}"
            + "|login=${MARK_LOGIN-}|loaded=${__kortty_si_loaded-}|zdotdir=${ZDOTDIR-unset}"
            + "|handover=${+KORTTY_SI_ZDOTDIR}|leftover=${+__kortty_si_zdotdir}|hist=${HISTFILE-}"
            + "|hooks=${(j:,:)precmd_functions}]\"";

        Map<String, String> fields = run(planFor(List.of("zsh", "-i"), Map.of()), "-c", probe);
        assertThat(fields).containsEntry("env", "env");
        assertThat(fields).containsEntry("rc", "rc");
        assertThat(fields).containsEntry("profile", "");
        assertThat(fields).containsEntry("loaded", "1");
        assertThat(fields).containsEntry("zdotdir", "unset");
        assertThat(fields).containsEntry("handover", "0");
        assertThat(fields).containsEntry("leftover", "0");
        // The snippet came after the user's .zshrc: its hooks are first and last.
        assertThat(fields.get("hooks")).startsWith("__kortty_si_precmd,");
        assertThat(fields.get("hooks")).contains(",user_hook,");
        assertThat(fields.get("hooks")).endsWith(",__kortty_si_prompt");
        assertWithMessage("a global zshrc's history must not point into the wrapper")
            .that(fields.get("hist")).doesNotContain(wrapper.shellPath());

        Map<String, String> login = run(planFor(List.of("zsh", "-l", "-i"), Map.of()), "-c", probe);
        assertThat(login).containsEntry("profile", "profile");
        assertThat(login).containsEntry("rc", "rc");
        assertThat(login).containsEntry("login", "login");
        assertThat(login).containsEntry("loaded", "1");
        assertThat(login).containsEntry("zdotdir", "unset");
    }

    @Test(timeOut = 60_000)
    void aZshFollowsTheZdotdirOfTheEnvironmentAndOfTheUsersZshenv() throws Exception {
        requireShell("zsh");
        Path config = Files.createDirectories(home.resolve(".config/zsh"));
        Files.writeString(config.resolve(".zshrc"), "MARK_RC=config\n");
        String probe = "print -r -- \"[rc=${MARK_RC-}|loaded=${__kortty_si_loaded-}|zdotdir=${ZDOTDIR-unset}]\"";

        // ZDOTDIR set where korTTY started: handed over to the wrapper and back.
        Map<String, String> fromEnvironment = run(
            planFor(List.of("zsh", "-i"), Map.of("ZDOTDIR", config.toString())), "-c", probe);
        assertThat(fromEnvironment).containsEntry("rc", "config");
        assertThat(fromEnvironment).containsEntry("loaded", "1");
        assertThat(fromEnvironment).containsEntry("zdotdir", config.toString());

        // ZDOTDIR set by ~/.zshenv, the usual way to move the zsh files.
        Files.writeString(home.resolve(".zshenv"), "export ZDOTDIR=$HOME/.config/zsh\n");
        Map<String, String> fromZshenv = run(planFor(List.of("zsh", "-i"), Map.of()), "-c", probe);
        assertThat(fromZshenv).containsEntry("rc", "config");
        assertThat(fromZshenv).containsEntry("loaded", "1");
        assertThat(fromZshenv).containsEntry("zdotdir", config.toString());
    }

    @Test(timeOut = 60_000)
    void aNonInteractiveZshGetsItsZdotdirBackAtOnce() throws Exception {
        requireShell("zsh");
        Map<String, String> fields = run(planFor(List.of("zsh"), Map.of()), "-c",
            "print -r -- \"[zdotdir=${ZDOTDIR-unset}|loaded=${__kortty_si_loaded-}]\"");
        assertThat(fields).containsEntry("zdotdir", "unset");
        assertThat(fields).containsEntry("loaded", "");
    }

    @Test(timeOut = 60_000)
    void aBashRunsTheUsersBashrcThenTheSnippet() throws Exception {
        requireShell("bash");
        Files.writeString(home.resolve(".bashrc"), "MARK_RC=rc\n");
        Files.writeString(home.resolve(".bash_profile"), "MARK_PROFILE=profile\n");
        String probe = "echo \"[rc=${MARK_RC-}|profile=${MARK_PROFILE-}|loaded=${__kortty_si_loaded-}"
            + "|version=${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}]\"";

        Map<String, String> fields = run(planFor(List.of("bash"), Map.of()), "-i", "-c", probe);
        assertThat(fields).containsEntry("rc", "rc");
        assertThat(fields).containsEntry("profile", "");
        assertThat(fields).containsEntry("loaded", supportsSnippet(fields.get("version")) ? "1" : "");

        Map<String, String> login = run(planFor(List.of("bash", "--login"), Map.of()), "-i", "-c", probe);
        assertThat(login).containsEntry("profile", "profile");
        assertThat(login).containsEntry("rc", "");
        assertThat(login).containsEntry("loaded", supportsSnippet(login.get("version")) ? "1" : "");
    }

    @Test(timeOut = 60_000)
    void aFishSourcesTheSnippetAfterItsConfiguration() throws Exception {
        requireShell("fish");
        Path conf = Files.createDirectories(home.resolve(".config/fish"));
        Files.writeString(conf.resolve("config.fish"), "set -g MARK_RC rc\n");
        Map<String, String> fields = run(planFor(List.of("fish", "-i"), Map.of()), "-c",
            "echo \"[rc=$MARK_RC|loaded=$__kortty_si_loaded]\"");
        assertThat(fields).containsEntry("rc", "rc");
        assertThat(fields).containsEntry("loaded", "1");
    }

    private Plan planFor(List<String> command, Map<String, String> extra) throws IOException {
        Map<String, String> env = new HashMap<>(baseEnvironment());
        env.putAll(extra);
        wrapper = ShellIntegrationWrapperDirectory.create(root);
        Plan plan = ShellIntegrationInjection.plan(command, env, wrapper.shellPath(), false).orElseThrow();
        wrapper.write(plan.files());
        return plan;
    }

    private Map<String, String> baseEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.toString());
        env.put("TERM", "xterm-256color");
        env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("LANG", "C");
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("XDG_DATA_HOME", home.resolve(".local/share").toString());
        return env;
    }

    /** Runs the plan's command with {@code extraArguments} appended; the fields of the last {@code [...]} line. */
    private Map<String, String> run(Plan plan, String... extraArguments) throws Exception {
        List<String> command = new ArrayList<>(plan.command());
        command.addAll(List.of(extraArguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = builder.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        assertWithMessage("%s did not finish", command).that(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(stdout, StandardCharsets.UTF_8);
        Matcher line = Pattern.compile("\\[([^\\]\\n]*)\\]\\s*$").matcher(out);
        assertWithMessage("no probe output from %s; stdout: %s; stderr: %s", command, out,
            new String(stderr, StandardCharsets.UTF_8)).that(line.find()).isTrue();
        Map<String, String> fields = new HashMap<>();
        for (String field : line.group(1).split("\\|")) {
            int eq = field.indexOf('=');
            if (eq > 0) {
                fields.put(field.substring(0, eq), field.substring(eq + 1));
            }
        }
        return fields;
    }

    /** bash 4.4 or later: the snippet does nothing in an older one, such as macOS's bash 3.2. */
    private static boolean supportsSnippet(String version) {
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return major > 4 || (major == 4 && minor >= 4);
    }

    private static void assertParses(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertWithMessage("%s did not finish", command).that(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertWithMessage("%s: %s", command, output).that(process.exitValue()).isEqualTo(0);
    }

    private static void requireShell(String shell) {
        if (!available(shell)) {
            throw new SkipException(shell + " is not installed");
        }
    }

    private static boolean available(String shell) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, shell))) {
                return true;
            }
        }
        return false;
    }
}
