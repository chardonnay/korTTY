package de.kortty.core.remote;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback-only Apache SSHD server whose exec channels run {@code /bin/sh -c <command>} as real
 * local processes, with extra directories in front of PATH (for fake tools such as a scripted
 * {@code sudo}) and extra environment variables the test can change between runs. Unix only.
 */
final class ExecLoopbackServer implements AutoCloseable {

    private final SshServer server;
    private final SshClient client;
    private final Map<String, String> environment = new ConcurrentHashMap<>();
    private final AtomicInteger closedChannels = new AtomicInteger();
    private volatile long lastStartMillis;
    private volatile long lastFirstStdinMillis;

    ExecLoopbackServer(Path hostKey, Path pathPrefix) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> true);
        server.setCommandFactory((channel, command) -> new ProcessCommand(command, pathPrefix));
        server.addChannelListener(new ChannelListener() {
            @Override
            public void channelClosed(Channel channel, Throwable reason) {
                closedChannels.incrementAndGet();
            }
        });
        server.start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, key) -> true);
        client.start();
    }

    /** Environment variables every later command sees. */
    Map<String, String> environment() {
        return environment;
    }

    /** How many channels the server has seen closed. */
    int closedChannels() {
        return closedChannels.get();
    }

    /** Milliseconds between the start of the last command and the first stdin byte it got, or -1. */
    long lastFirstStdinDelayMillis() {
        long first = lastFirstStdinMillis;
        return first == 0 ? -1 : first - lastStartMillis;
    }

    ClientSession connect() throws IOException {
        ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
            .verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity("login-password");
        session.auth().verify(Duration.ofSeconds(10));
        return session;
    }

    @Override
    public void close() throws IOException {
        client.stop();
        server.stop(true);
    }

    private final class ProcessCommand implements Command {
        private final String command;
        private final Path pathPrefix;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;
        private volatile Process process;
        private volatile boolean finished;

        ProcessCommand(String command, Path pathPrefix) {
            this.command = command;
            this.pathPrefix = pathPrefix;
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
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
            Map<String, String> processEnv = builder.environment();
            processEnv.putAll(environment);
            processEnv.put("PATH", pathPrefix + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            lastFirstStdinMillis = 0;
            lastStartMillis = System.currentTimeMillis();
            Process started = builder.start();
            process = started;
            daemon("exec-fixture-in", () -> {
                try (OutputStream processIn = started.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read > 0 && lastFirstStdinMillis == 0) {
                            lastFirstStdinMillis = System.currentTimeMillis();
                        }
                        processIn.write(buffer, 0, read);
                        processIn.flush();
                    }
                } catch (IOException ignored) {
                    // the process ended or the channel closed
                }
            });
            Thread stdout = daemon("exec-fixture-out", () -> pump(started.getInputStream(), out));
            Thread stderr = daemon("exec-fixture-err", () -> pump(started.getErrorStream(), err));
            daemon("exec-fixture-wait", () -> {
                try {
                    int code = started.waitFor();
                    stdout.join();
                    stderr.join();
                    finished = true;
                    exit.onExit(code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public void destroy(ChannelSession channel) {
            Process running = process;
            if (running != null && !finished) {
                running.descendants().forEach(ProcessHandle::destroyForcibly);
                running.destroyForcibly();
            }
        }

        private void pump(InputStream source, OutputStream target) {
            byte[] buffer = new byte[8192];
            int read;
            try {
                while ((read = source.read(buffer)) >= 0) {
                    target.write(buffer, 0, read);
                    target.flush();
                }
            } catch (IOException ignored) {
                // channel closed
            }
        }

        private Thread daemon(String name, Runnable body) {
            Thread thread = new Thread(body, name);
            thread.setDaemon(true);
            thread.start();
            return thread;
        }
    }
}
