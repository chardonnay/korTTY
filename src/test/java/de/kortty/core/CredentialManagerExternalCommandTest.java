package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import de.kortty.core.agent.LocalShellArgv;
import de.kortty.model.StoredCredential;
import de.kortty.ui.I18n;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class CredentialManagerExternalCommandTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(10);

    private static void requirePosix() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("POSIX shell test");
        }
    }

    private static IOException runExpectingFailure(String command, Duration timeout) throws Exception {
        try {
            String result = CredentialManager.executeExternalCommand(command, timeout);
            throw new AssertionError("Expected the command to fail, but it returned " + result.length() + " characters");
        } catch (IOException expected) {
            return expected;
        }
    }

    @Test(timeOut = 20_000)
    void returnsFirstLineOfStdout() throws Exception {
        requirePosix();
        assertThat(CredentialManager.executeExternalCommand("printf 'secret\\nsecond\\n'", GENEROUS_TIMEOUT))
            .isEqualTo("secret");
    }

    @Test(timeOut = 20_000)
    void timesOutWhileStdoutStaysOpen() throws Exception {
        requirePosix();
        long start = System.nanoTime();

        IOException error = runExpectingFailure("sleep 30", SHORT_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.timeout", 1));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000L);
    }

    @Test(timeOut = 20_000)
    void timeoutKillsTheProcessesTheCommandStarted() throws Exception {
        requirePosix();
        Path pidFile = Files.createTempFile("kortty-credential-command-", ".pid");
        try {
            String command = "sleep 30 & echo $! > '" + pidFile + "'; wait";

            IOException error = runExpectingFailure(command, Duration.ofSeconds(2));

            assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.timeout", 2));
            long childPid = Long.parseLong(Files.readString(pidFile).trim());
            Optional<ProcessHandle> child = ProcessHandle.of(childPid);
            if (child.isPresent()) {
                child.get().onExit().get(5, TimeUnit.SECONDS);
            }
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test(timeOut = 20_000)
    void stdinIsClosedSoReadersDoNotHang() throws Exception {
        requirePosix();
        long start = System.nanoTime();

        IOException error = runExpectingFailure("read x; printf '%s' \"$x\"", GENEROUS_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.empty"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000L);
    }

    @Test(timeOut = 20_000)
    void catGetsEofAndFailsWithEmptyOutput() throws Exception {
        requirePosix();
        IOException error = runExpectingFailure("cat", GENEROUS_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.empty"));
    }

    @Test(timeOut = 20_000)
    void largeStderrDoesNotDeadlock() throws Exception {
        requirePosix();
        assertThat(CredentialManager.executeExternalCommand(
            "head -c 300000 /dev/zero | tr '\\0' x 1>&2; echo ok", GENEROUS_TIMEOUT))
            .isEqualTo("ok");
    }

    @Test(timeOut = 20_000)
    void largeStdoutIsCappedAndStillYieldsTheFirstLine() throws Exception {
        requirePosix();
        assertThat(CredentialManager.executeExternalCommand(
            "printf 'secret\\n'; head -c 300000 /dev/zero | tr '\\0' y", GENEROUS_TIMEOUT))
            .isEqualTo("secret");
    }

    @Test(timeOut = 20_000)
    void endlessOutputStillHitsTheDeadline() throws Exception {
        requirePosix();
        long start = System.nanoTime();

        IOException error = runExpectingFailure("yes", SHORT_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.timeout", 1));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000L);
    }

    @Test(timeOut = 20_000)
    void nonZeroExitReportsStderr() throws Exception {
        requirePosix();
        IOException error = runExpectingFailure("echo boom 1>&2; exit 3", GENEROUS_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.failed", 3, "boom"));
    }

    @Test(timeOut = 20_000)
    void nonZeroExitWithoutStderrStillNamesTheExitCode() throws Exception {
        requirePosix();
        IOException error = runExpectingFailure("echo partial; exit 4", GENEROUS_TIMEOUT);

        assertThat(error).hasMessageThat().isEqualTo(I18n.get("credential.externalCommand.error.failed", 4, "").trim());
        assertThat(error).hasMessageThat().doesNotContain("partial");
    }

    @Test(timeOut = 20_000)
    void decodesUtf8() throws Exception {
        requirePosix();
        // Octal escapes keep the test independent of the JVM's argv encoding: these are the UTF-8
        // bytes of "pässwörd".
        assertThat(CredentialManager.executeExternalCommand("printf 'p\\303\\244ssw\\303\\266rd'", GENEROUS_TIMEOUT))
            .isEqualTo("pässwörd");
    }

    @Test
    void posixArgvRunsTheCommandThroughBinSh() {
        assertThat(CredentialManager.externalCommandArgv("op read op://vault/item/password", ShellKind.POSIX, Map.of()))
            .containsExactly("/bin/sh", "-c", "op read op://vault/item/password")
            .inOrder();
    }

    @Test
    void powerShellArgvPropagatesNativeExitCodes() {
        String command = "op item get x --fields password # trailing comment";

        List<String> argv = CredentialManager.externalCommandArgv(command, ShellKind.WINDOWS_POWERSHELL, Map.of());

        assertThat(argv.get(0)).isEqualTo("powershell.exe");
        assertThat(argv).contains("-NonInteractive");
        String script = new String(Base64.getDecoder().decode(argv.get(argv.size() - 1)), StandardCharsets.UTF_16LE);
        assertThat(script).isEqualTo(
            LocalShellArgv.POWERSHELL_UTF8_PREFIX + command + "\nif ($LASTEXITCODE) { exit $LASTEXITCODE }");
    }

    @Test
    void flatpakArgvSpawnsTheCommandOnTheHost() {
        List<String> argv = CredentialManager.externalCommandArgv(
            "bw get password x", ShellKind.POSIX, Map.of("FLATPAK_ID", "io.github.chardonnay.korTTY"));

        assertThat(argv.subList(0, 2)).containsExactly("flatpak-spawn", "--host").inOrder();
        assertThat(argv).contains("--directory=" + System.getProperty("user.home"));
        assertThat(argv.subList(argv.size() - 3, argv.size()))
            .containsExactly("/bin/sh", "-c", "bw get password x")
            .inOrder();
    }

    @Test(timeOut = 30_000)
    void getPasswordAsyncRunsTheCommandInTheBackground() throws Exception {
        requirePosix();
        char[] master = "master-password".toCharArray();
        CredentialManager manager = new CredentialManager(Files.createTempDirectory("kortty-credentials-"));
        StoredCredential credential = externalCommandCredential(manager, "printf 'async-secret\\n'", master);

        assertThat(manager.getPasswordAsync(credential, master).get(20, TimeUnit.SECONDS)).isEqualTo("async-secret");
    }

    @Test(timeOut = 30_000)
    void getPasswordAsyncCompletesWithTheCommandsError() throws Exception {
        requirePosix();
        char[] master = "master-password".toCharArray();
        CredentialManager manager = new CredentialManager(Files.createTempDirectory("kortty-credentials-"));
        StoredCredential credential = externalCommandCredential(manager, "echo boom 1>&2; exit 3", master);

        try {
            manager.getPasswordAsync(credential, master).get(20, TimeUnit.SECONDS);
            throw new AssertionError("Expected the password command to fail");
        } catch (ExecutionException e) {
            assertThat(e).hasCauseThat().isInstanceOf(IOException.class);
            assertThat(e).hasCauseThat().hasMessageThat().contains("boom");
        }
    }

    private static StoredCredential externalCommandCredential(CredentialManager manager, String command, char[] master)
        throws Exception {
        StoredCredential credential = new StoredCredential();
        credential.setName("vault");
        credential.setUsername("deploy");
        credential.setPasswordType(StoredCredential.PasswordType.EXTERNAL_COMMAND);
        manager.setExternalCommand(credential, command, master);
        return credential;
    }
}
