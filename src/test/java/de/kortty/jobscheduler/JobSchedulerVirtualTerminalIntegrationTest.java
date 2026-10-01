package de.kortty.jobscheduler;

import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

/** Runs {@link JobSchedulerRemoteSession#executeInPty} against an in-process SSH server. */
class JobSchedulerVirtualTerminalIntegrationTest {

    private SshServer server;
    private final AtomicReference<String> ptyType = new AtomicReference<>();

    @BeforeMethod
    void startServer() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-pty-test-");
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPasswordAuthenticator((u, p, s) -> true);
        server.setCommandFactory((channel, command) -> new ScriptedCommand(command, ptyType));
        server.start();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
    }

    private JobSchedulerRemoteSession connect() throws Exception {
        ServerConnection connection = new ServerConnection("pty", "127.0.0.1", server.getPort(), "u");
        char[] master = "master-pw".toCharArray();
        connection.setEncryptedPassword(new de.kortty.security.EncryptionService().encryptPassword("pw", master));
        JobSchedulerRemoteSession session = new JobSchedulerRemoteSession(null, connection, null, master, true);
        session.connect();
        return session;
    }

    @Test
    void streamsColoredOutputAndTheExitCodeThroughAPseudoTerminal() throws Exception {
        StringBuilder output = new StringBuilder();
        try (JobSchedulerRemoteSession session = connect()) {
            JobSchedulerRemoteSession.PtyResult result = session.executeInPty(
                "colors", null, 100, 30, "xterm-256color", null, output::append, null);

            assertThat(result.limitReached()).isFalse();
            assertThat(result.exitCode()).isEqualTo(3);
        }
        assertThat(output.toString()).contains("\u001b[32mgreen\u001b[0m äöü");
        assertThat(ptyType.get()).isEqualTo("xterm-256color");
    }

    @Test
    void theRuntimeLimitSendsCtrlCAndReportsIt() throws Exception {
        StringBuilder output = new StringBuilder();
        try (JobSchedulerRemoteSession session = connect()) {
            JobSchedulerRemoteSession.PtyResult result = session.executeInPty(
                "forever", null, 80, 24, null, Duration.ofSeconds(1), output::append, null);

            assertThat(result.limitReached()).isTrue();
            assertThat(result.exitCode()).isEqualTo(-1);
        }
        assertThat(output.toString()).contains("running");
        assertThat(output.toString()).contains("got ctrl-c");
    }

    /** "colors" prints ANSI + UTF-8 and exits 3; "forever" runs until it reads Ctrl+C. */
    private static final class ScriptedCommand implements Command {
        private final String command;
        private final AtomicReference<String> ptyType;
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;

        ScriptedCommand(String command, AtomicReference<String> ptyType) {
            this.command = command;
            this.ptyType = ptyType;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            ptyType.set(env.getEnv().get("TERM"));
            Thread worker = new Thread(() -> {
                try {
                    if (command.contains("forever")) {
                        out.write("running\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        int read;
                        while ((read = in.read()) != -1) {
                            if (read == 3) {
                                out.write("got ctrl-c\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                exit.onExit(130);
                                return;
                            }
                        }
                        exit.onExit(0);
                    } else {
                        out.write("\u001b[32mgreen\u001b[0m äöü\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        exit.onExit(3);
                    }
                } catch (Exception e) {
                    exit.onExit(1);
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }
}
