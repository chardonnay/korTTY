package de.kortty.core.remote;

import de.kortty.core.TerminalAgentService.AgentCancelledException;
import de.kortty.core.agent.AgentCommandRunner.ExecResult;
import de.kortty.core.agent.AgentSudoAuthentication;
import de.kortty.core.agent.AgentSudoPty;
import de.kortty.core.agent.AgentSudoTestSupport;
import org.apache.sshd.client.session.ClientSession;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** Exercises the production SSH channel and command framing over a real Unix controlling terminal. */
class SshAgentSudoPtyIntegrationTest {

    private static final String SECRET = UUID.randomUUID() + " ä'\"$";
    private static final String TTY_ID = "LC_ALL=C ps -p \"$$\" -o tty= | tr -d '[:space:]'";
    private static final String SUDO = """
        #!/bin/sh
        prompt=
        validate=false
        while [ "$#" -gt 0 ]; do
            case "$1" in
                -S|-n) shift ;;
                -p) prompt=$2; shift 2 ;;
                -v) validate=true; shift ;;
                --) shift; break ;;
                *) break ;;
            esac
        done
        terminal=$(%s) || exit 31
        case "$terminal" in ''|'?'|'??') exit 31 ;; esac
        if "$validate"; then
            [ -t 0 ] || exit 32
            stty -a | grep -Eq '(^|[[:space:]])-echo([[:space:];]|$)' || exit 33
            printf '%%s' "$prompt" >&2
            IFS= read -r supplied || exit 34
            [ "$supplied" = "$FAKE_SUDO_PASSWORD" ] || exit 35
            printf '%%s\\n' "$terminal" > "$FAKE_SUDO_TICKET"
            printf 'validated\\n' >> "$FAKE_SUDO_LOG"
            exit 0
        fi
        [ -f "$FAKE_SUDO_TICKET" ] && [ "$(cat "$FAKE_SUDO_TICKET")" = "$terminal" ] || exit 36
        exec "$@"
        """.formatted(TTY_ID);

    private Path directory;
    private Path sudo;
    private Path ticket;
    private Path sudoLog;
    private ExecLoopbackServer server;
    private ClientSession session;

    @BeforeMethod
    void startServer() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("requires a real Unix controlling terminal");
        }
        directory = Files.createTempDirectory("kortty-agent-pty-");
        Path bin = Files.createDirectory(directory.resolve("bin"));
        sudo = bin.resolve("sudo");
        Files.writeString(sudo, SUDO);
        Files.setPosixFilePermissions(sudo, PosixFilePermissions.fromString("rwxr-xr-x"));
        ticket = directory.resolve("sudo.ticket");
        sudoLog = directory.resolve("sudo.log");
        server = new ExecLoopbackServer(directory.resolve("host.ser"), bin, true, false, true);
        server.environment().put("FAKE_SUDO_PASSWORD", SECRET);
        server.environment().put("FAKE_SUDO_TICKET", ticket.toString());
        server.environment().put("FAKE_SUDO_LOG", sudoLog.toString());
        session = server.connect();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() throws IOException {
        if (session != null) {
            session.close(true);
        }
        if (server != null) {
            server.close();
        }
        if (directory != null) {
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test(timeOut = 20000)
    void reusesOneTerminalAndIgnoresPasswordPromptsFromPlannedCommands() throws Exception {
        AtomicInteger passwordRequests = new AtomicInteger();
        String replay;
        AgentSudoPty opened;
        try (AgentSudoAuthentication auth = authentication(passwordRequests)) {
            replay = AgentSudoTestSupport.promptMarker(auth);
            opened = open(auth);
            auth.checkFailure();
        }
        try (AgentSudoPty pty = opened) {
            String terminal = Files.readString(ticket).strip();
            assertThat(terminal).isNotEmpty();
            ExecResult first = pty.exec("sudo -n sh -c "
                + RemoteShell.quote(TTY_ID + "; printf '\\n'; printf out; printf err >&2; exit 17"), null, null);
            assertThat(first.exitCode()).isEqualTo(17);
            assertThat(first.stdout() + first.stderr()).contains(terminal + "\n");
            assertThat(first.stdout() + first.stderr()).contains("out");
            assertThat(first.stdout() + first.stderr()).contains("err");

            ExecResult second = pty.exec("""
                sudo -n cat <<'END'
                heredoc
                END
                printf '%%s' %s >/dev/tty
                if IFS= read -r value; then
                    printf 'unexpected command input: %%s\\n' "$value"
                    exit 19
                fi
                sudo -n sh -c %s
                """.formatted(RemoteShell.quote(replay), RemoteShell.quote(TTY_ID + "; printf '\\n'")), null, null);
            assertThat(second.exitCode()).isEqualTo(0);
            String output = first.stdout() + first.stderr() + second.stdout() + second.stderr();
            assertThat(output).contains("heredoc\n");
            assertThat(second.stdout() + second.stderr()).contains(terminal + "\n");
            assertThat(output).doesNotContain(SECRET);
            assertThat(output).doesNotContain("unexpected command input");
            assertThat(Files.readAllLines(sudoLog)).containsExactly("validated");
            assertThat(passwordRequests.get()).isEqualTo(1);
            assertThat(server.ptyChannels()).isEqualTo(1);

            ExecResult third = pty.exec("pwd -P; printf '%s\\n' 'quoted ä path'; exit 23", null, null);
            assertThat(third.exitCode()).isEqualTo(23);
            assertThat(third.stdout()).contains(directory.toRealPath().toString() + "\n");
            assertThat(third.stdout()).contains("quoted ä path\n");
        }
        await(() -> server.closedChannels() == 1);
    }

    @Test(timeOut = 20000)
    void cancellationClosesThePtyAndKeepsTheSshConnection() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Path started = directory.resolve("started");
        try (AgentSudoAuthentication auth = authentication(new AtomicInteger());
             AgentSudoPty pty = open(auth);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> pty.exec("sudo -n sh -c "
                + RemoteShell.quote("printf started > " + RemoteShell.quote(started.toString()) + "; exec sleep 30"),
                null, cancelled::get));
            try {
                await(() -> Files.exists(started));
            } finally {
                cancelled.set(true);
            }
            ExecutionException failure = expectThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertThat(failure).hasCauseThat().isInstanceOf(AgentCancelledException.class);
            await(() -> server.closedChannels() == 1);
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test(timeOut = 20000)
    void serverWithoutARealPtyCannotRequestAPassword() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (ExecLoopbackServer withoutPty =
                 new ExecLoopbackServer(directory.resolve("without-pty.ser"), sudo.getParent());
             ClientSession plainSession = withoutPty.connect();
             AgentSudoAuthentication auth = authentication(requests)) {
            IOException failure = expectThrows(IOException.class, () ->
                AgentSudoTestSupport.openPty(plainSession, directory::toString, auth, () -> false, sudo.toString()));
            assertThat(failure).hasMessageThat().contains("did not allocate a terminal");
            assertThat(requests.get()).isEqualTo(0);
            await(() -> withoutPty.closedChannels() == 1);
        }
    }

    private AgentSudoPty open(AgentSudoAuthentication auth) throws Exception {
        return AgentSudoTestSupport.openPty(session, directory::toString, auth, () -> false, sudo.toString());
    }

    private static AgentSudoAuthentication authentication(AtomicInteger requests) {
        return new AgentSudoAuthentication("sudo -n fixture", retry -> {
            requests.incrementAndGet();
            return (SECRET + "\n").getBytes(StandardCharsets.UTF_8);
        }, output -> output);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
