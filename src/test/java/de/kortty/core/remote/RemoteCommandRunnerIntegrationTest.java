package de.kortty.core.remote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.LoggerFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Drives {@link RemoteCommandRunner} against a loopback SSH server whose exec channels run real
 * local shells, with a scripted {@code sudo} first on PATH (a shell script, not a renamed binary,
 * so it works with uutils' multicall coreutils too). Unix only.
 */
public class RemoteCommandRunnerIntegrationTest {

    private static final String SECRET = "S3cret pw'\"$x";
    private static final String FAKE_SUDO = """
        #!/bin/sh
        prompt="Password:"
        nonint=0
        while [ $# -gt 0 ]; do
          case "$1" in
            -S) shift ;;
            -n) nonint=1; shift ;;
            -p) prompt="$2"; shift 2 ;;
            --) shift; break ;;
            -*) shift ;;
            *) break ;;
          esac
        done
        log="${FAKE_SUDO_LOG:-/dev/null}"
        case "${FAKE_SUDO_MODE:-password}" in
          requiretty) echo "sudo: sorry, you must have a tty to run sudo" >&2; exit 1 ;;
          nopasswd) echo "nopasswd-run" >> "$log"; exec "$@" ;;
        esac
        if [ "$nonint" = 1 ]; then echo "sudo: a password is required" >&2; exit 1; fi
        sleep 0.6
        attempt=0
        while [ $attempt -lt 3 ]; do
          attempt=$((attempt + 1))
          printf '%s' "$prompt" >&2
          IFS= read -r line || { echo "sudo: no password was provided" >&2; exit 1; }
          echo "got:$line" >> "$log"
          if [ "$line" = "$FAKE_SUDO_PASSWORD" ]; then exec "$@"; fi
          echo "Sorry, try again." >&2
        done
        echo "sudo: 3 incorrect password attempts" >&2
        exit 1
        """;

    private Path dir;
    private Path sudoLog;
    private ExecLoopbackServer server;
    private ClientSession session;
    private RemoteCommandRunner runner;
    private Logger root;
    private Logger remoteLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;
    private final List<SudoCommand> issuedCommands = new ArrayList<>();
    private final List<String> errorTexts = new ArrayList<>();

    @BeforeClass
    public void startServer() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("exec fixture runs /bin/sh");
        }
        dir = Files.createTempDirectory("kortty-remote-runner-");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path sudo = bin.resolve("sudo");
        Files.writeString(sudo, FAKE_SUDO.replace("\r\n", "\n"));
        Files.setPosixFilePermissions(sudo, PosixFilePermissions.fromString("rwxr-xr-x"));
        sudoLog = dir.resolve("sudo.log");
        server = new ExecLoopbackServer(dir.resolve("host.ser"), bin);
        server.environment().put("FAKE_SUDO_LOG", sudoLog.toString());
        server.environment().put("FAKE_SUDO_PASSWORD", SECRET);
        session = server.connect();
        runner = new RemoteCommandRunner(() -> session);
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() throws IOException {
        if (session != null) {
            session.close(true);
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeMethod
    public void reset() throws IOException {
        Files.deleteIfExists(sudoLog);
        server.environment().put("FAKE_SUDO_MODE", "password");
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        remoteLogger = (Logger) LoggerFactory.getLogger("de.kortty.core.remote");
        previousLevel = remoteLogger.getLevel();
        remoteLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        issuedCommands.clear();
        errorTexts.clear();
    }

    @AfterMethod(alwaysRun = true)
    public void assertNoSecretLeaked() {
        root.detachAppender(appender);
        remoteLogger.setLevel(previousLevel);
        List<String> texts = new ArrayList<>(errorTexts);
        for (ILoggingEvent event : appender.list) {
            texts.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                texts.add(String.valueOf(proxy.getMessage()));
            }
        }
        for (String text : texts) {
            assertThat(text).doesNotContain(SECRET);
            for (SudoCommand command : issuedCommands) {
                assertThat(text).doesNotContain(command.promptNonce());
                assertThat(text).doesNotContain(command.readyNonce());
            }
        }
    }

    private SudoCommand sudo(String inner) {
        SudoCommand command = SudoCommand.wrap(inner);
        issuedCommands.add(command);
        return command;
    }

    private RemoteCommandRunner.Result runSudo(SudoCommand command, String secret, byte[] stdin) throws IOException {
        try {
            RemoteCommandRunner.Result result = runner.run(command,
                Optional.ofNullable(secret).map(String::toCharArray),
                Optional.ofNullable(stdin).map(ByteArrayInputStream::new),
                Duration.ofSeconds(20), RemoteCommandRunner.DEFAULT_OUTPUT_CAP, new RemoteCommandCancellation());
            errorTexts.add(result.stderr());
            errorTexts.add(command.toString());
            return result;
        } catch (IOException e) {
            errorTexts.add(String.valueOf(e.getMessage()));
            errorTexts.add(String.valueOf(e));
            throw e;
        }
    }

    private List<String> sudoLog() throws IOException {
        return Files.exists(sudoLog) ? Files.readAllLines(sudoLog) : List.of();
    }

    @Test
    public void passwordIsSentOnlyAfterThePromptNonceAndStdinReachesTheCommand() throws Exception {
        Path out = dir.resolve("with-password.out");
        byte[] payload = "payload line 1\nline 2 ä\0binary\n".getBytes(StandardCharsets.UTF_8);

        RemoteCommandRunner.Result result = runSudo(
            sudo("cat > " + RemoteShell.quote(out.toString())), SECRET, payload);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readAllBytes(out)).isEqualTo(payload);
        assertThat(sudoLog()).containsExactly("got:" + SECRET);
        // the fake sudo waits 0.6 s before it prompts; a blind send would arrive at once
        assertThat(server.lastFirstStdinDelayMillis()).isAtLeast(500L);
        assertThat(result.stderr()).doesNotContain("KORTTY");
    }

    @Test
    public void withNopasswdNoPasswordIsSentAndStdinArrivesIntact() throws Exception {
        server.environment().put("FAKE_SUDO_MODE", "nopasswd");
        Path out = dir.resolve("nopasswd.out");
        byte[] payload = "exact bytes, no password before them\n".getBytes(StandardCharsets.UTF_8);

        RemoteCommandRunner.Result result = runSudo(
            sudo("cat > " + RemoteShell.quote(out.toString())), SECRET, payload);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readAllBytes(out)).isEqualTo(payload);
        assertThat(sudoLog()).containsExactly("nopasswd-run");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    public void stdoutAndExitCodeOfTheInnerCommandComeBack() throws Exception {
        server.environment().put("FAKE_SUDO_MODE", "nopasswd");

        RemoteCommandRunner.Result result = runSudo(sudo("printf 'a b'; echo oops >&2; exit 3"), null, null);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdoutText()).isEqualTo("a b");
        assertThat(result.stderr()).isEqualTo("oops\n");
    }

    @Test
    public void aWrongPasswordAbortsAfterTheSecondPrompt() throws Exception {
        SudoCommand command = sudo("true");

        assertThrows(SudoAuthenticationException.class, () -> runSudo(command, "wrong", null));

        assertThat(sudoLog()).containsExactly("got:wrong");
    }

    @Test
    public void aPromptWithoutAPasswordFailsWithoutSendingAnything() throws Exception {
        assertThrows(SudoPasswordRequiredException.class, () -> runSudo(sudo("true"), null, null));

        assertThat(sudoLog()).isEmpty();
    }

    @Test
    public void requirettyMapsToItsOwnException() {
        server.environment().put("FAKE_SUDO_MODE", "requiretty");

        assertThrows(SudoRequiresTtyException.class, () -> runSudo(sudo("true"), SECRET, null));
    }

    @Test
    public void cancelClosesTheChannel() throws Exception {
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        int closedBefore = server.closedChannels();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return;
            }
            cancellation.cancel();
        });
        long started = System.nanoTime();
        canceller.start();

        assertThrows(RemoteCommandCancelledException.class, () -> runner.run("sleep 30", Optional.empty(),
            Duration.ofSeconds(60), 1024, cancellation));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        long deadline = System.currentTimeMillis() + 5000;
        while (server.closedChannels() == closedBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        // the server saw the exec channel closed while `sleep 30` was still running
        assertThat(server.closedChannels()).isGreaterThan(closedBefore);
    }

    @Test
    public void aTimeoutClosesTheChannel() {
        assertThrows(RemoteCommandTimeoutException.class, () -> runner.run("sleep 30", Optional.empty(),
            Duration.ofMillis(500), 1024, new RemoteCommandCancellation()));
    }

    @Test
    public void outputStopsAtTheCap() throws Exception {
        RemoteCommandRunner.Result result = runner.run("while :; do echo 0123456789; done", Optional.empty(),
            Duration.ofSeconds(20), 1000, new RemoteCommandCancellation());

        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout().length).isEqualTo(1000);
    }

    @Test
    public void aSinkCanStopTheCommand() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        RemoteCommandRunner.Result result = runner.run(RemoteCommandRunner.Request
            .plain("while :; do echo line; done")
            .timeout(Duration.ofSeconds(20))
            .stdoutSink((data, offset, length) -> {
                first.compareAndSet(null, new String(data, offset, length, StandardCharsets.UTF_8));
                return false;
            }));

        assertThat(result.stoppedBySink()).isTrue();
        assertThat(first.get()).startsWith("line");
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    public void plainCommandsGetStdinAndEof() throws Exception {
        RemoteCommandRunner.Result result = runner.run("wc -c", Optional.of(new ByteArrayInputStream(new byte[12345])),
            Duration.ofSeconds(20), 1024, new RemoteCommandCancellation());

        assertThat(result.stdoutText().trim()).isEqualTo("12345");
    }

    @Test
    public void sudoProbeReportsWhetherAPasswordIsNeeded() throws Exception {
        server.environment().put("FAKE_SUDO_MODE", "nopasswd");
        assertThat(SudoProbe.canRunWithoutPassword(runner)).isTrue();

        server.environment().put("FAKE_SUDO_MODE", "password");
        assertThat(SudoProbe.canRunWithoutPassword(runner)).isFalse();
        // -n never prompts, so nothing was read as a password
        assertThat(sudoLog()).containsExactly("nopasswd-run");
    }

    @Test
    public void aMissingSessionFailsCleanly() {
        RemoteCommandRunner disconnected = new RemoteCommandRunner(() -> null);

        assertThrows(RemoteCommandException.class, () -> disconnected.run("true", Optional.empty(),
            Duration.ofSeconds(5), 1024, new RemoteCommandCancellation()));
    }
}
