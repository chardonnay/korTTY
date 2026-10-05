package de.kortty.core.worker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.core.SshLivenessProbe;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.common.CommonModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A session worker: one SSH connection of korTTY, run in a process of its own.
 *
 * <p>The worker reads a {@link WorkerInit} as the first line of its stdin, connects and
 * authenticates to the server (through the jump server, if any) and then offers korTTY a loopback
 * SSH endpoint on {@code 127.0.0.1} that only accepts korTTY's token. Every channel korTTY opens
 * there — its shell, the AI agent's commands, SFTP, {@code -L}/{@code -D} tunnels — is relayed to a
 * channel of the same kind on the real server. Everything that needs a person or a secret is asked
 * of korTTY over the control channel on stdin/stdout: whether to trust a host key, the answers to
 * keyboard-interactive prompts, and signatures for key authentication. The worker never sees the
 * vault, the master password or a private key, and it writes no file.
 *
 * <p>It exits when korTTY closes its stdin, when korTTY's last session on the endpoint ends, or when
 * the server connection ends; korTTY then sees its own session close and reports the reason.
 */
public final class SessionWorkerMain {

    /** Exit code: the connection or login failed; the reason was sent as a {@code failed} message. */
    static final int EXIT_CONNECT_FAILED = 2;
    /** Exit code: korTTY sent nothing usable. */
    static final int EXIT_BAD_INIT = 3;

    private static Logger logger;

    private SessionWorkerMain() {
    }

    public static void main(String[] args) {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-worker.xml");
        }
        // stdout is the control channel: nothing else may ever print there.
        PrintStream control = System.out;
        System.setOut(System.err);
        logger = LoggerFactory.getLogger(SessionWorkerMain.class);
        int code;
        try {
            code = run(System.in, control);
        } catch (Throwable t) {
            logger.error("Session worker failed: {}", t.toString(), t);
            code = 1;
        }
        System.exit(code);
    }

    static int run(InputStream stdin, OutputStream stdout) throws Exception {
        // The first line is the init; the endpoint reads every line after it.
        String initLine = readLine(stdin);
        WorkerInit init;
        try {
            init = new Gson().fromJson(initLine, WorkerInit.class);
        } catch (RuntimeException e) {
            logger.error("Unreadable init from korTTY");
            return EXIT_BAD_INIT;
        }
        if (init == null || init.protocol != WorkerInit.PROTOCOL_VERSION || init.host == null
                || init.username == null || init.token == null || init.token.length() < 16) {
            logger.error("Unusable init from korTTY");
            return EXIT_BAD_INIT;
        }
        WorkerEndpoint endpoint = new WorkerEndpoint(stdin, stdout, "worker");
        CountDownLatch done = new CountDownLatch(1);
        endpoint.setEventListener(event -> {
            if ("shutdown".equals(event.has("type") ? event.get("type").getAsString() : "")) {
                done.countDown();
            }
        });
        endpoint.start();
        endpoint.ended().thenRun(done::countDown);

        Upstream upstream = new Upstream(init, endpoint);
        try {
            upstream.connect();
        } catch (ConnectFailure failure) {
            JsonObject failed = new JsonObject();
            failed.addProperty("type", "failed");
            failed.addProperty("kind", failure.kind);
            failed.addProperty("message", failure.getMessage());
            endpoint.send(failed);
            upstream.close();
            endpoint.close();
            return EXIT_CONNECT_FAILED;
        }

        SshServer server = startEndpoint(init, upstream, done);
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "ready");
        ready.addProperty("port", server.getPort());
        ready.addProperty("hostKey", PublicKeyEntry.toString(hostKey(server)));
        ready.addProperty("pid", ProcessHandle.current().pid());
        endpoint.send(ready);

        upstream.session.addSessionListener(new SessionListener() {
            @Override
            public void sessionClosed(Session session) {
                logger.info("Server connection ended");
                done.countDown();
            }
        });
        upstream.startLivenessProbe();
        done.await();
        logger.info("Session worker ends");
        try {
            server.stop(true);
        } catch (IOException ignored) {
            // Exiting anyway.
        }
        upstream.close();
        endpoint.close();
        return 0;
    }

    /** Reads one line of bytes without buffering past it, so the endpoint gets every byte after it. */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (line.size() > WorkerEndpoint.MAX_LINE_CHARS) {
                throw new IOException("init too long");
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static PublicKey hostKey(SshServer server) throws Exception {
        return server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
    }

    /** The loopback endpoint korTTY logs in to with its token. */
    private static SshServer startEndpoint(WorkerInit init, Upstream upstream, CountDownLatch done) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider();
        hostKeys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        hostKeys.setKeySize(256);
        server.setKeyPairProvider(hostKeys);
        byte[] token = init.token.getBytes(StandardCharsets.UTF_8);
        server.setPasswordAuthenticator((user, password, session) ->
            password != null && MessageDigest.isEqual(token, password.getBytes(StandardCharsets.UTF_8)));
        server.setPublickeyAuthenticator(null);
        server.setKeyboardInteractiveAuthenticator(null);
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setShellFactory(channel -> new RelayCommand(upstream.session, RelayCommand.Kind.SHELL, null));
        server.setCommandFactory((channel, command) -> new RelayCommand(upstream.session, RelayCommand.Kind.EXEC, command));
        server.setSubsystemFactories(List.of(relaySubsystem(upstream, "sftp")));
        server.setChannelFactories(List.of(ChannelSessionFactory.INSTANCE,
            DirectTcpipRelayChannel.factory(() -> upstream.session)));
        AtomicBoolean hadSession = new AtomicBoolean();
        AtomicLong openSessions = new AtomicLong();
        server.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                hadSession.set(true);
                openSessions.incrementAndGet();
            }

            @Override
            public void sessionClosed(Session session) {
                if (openSessions.decrementAndGet() <= 0 && hadSession.get()) {
                    done.countDown();
                }
            }
        });
        server.start();
        return server;
    }

    private static SubsystemFactory relaySubsystem(Upstream upstream, String name) {
        return new SubsystemFactory() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public org.apache.sshd.server.command.Command createSubsystem(
                    org.apache.sshd.server.channel.ChannelSession channel) {
                return new RelayCommand(upstream.session, RelayCommand.Kind.SUBSYSTEM, name);
            }
        };
    }

    /** Why the connection could not be made: {@code hostkey}, {@code auth}, {@code config} or {@code network}. */
    static final class ConnectFailure extends Exception {
        final String kind;

        ConnectFailure(String kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** The connection to the real server, and the jump server before it. */
    static final class Upstream {

        private final WorkerInit init;
        private final WorkerEndpoint endpoint;
        private final AtomicLong inboundBytes = new AtomicLong();
        private final AtomicBoolean hostKeyRejected = new AtomicBoolean();
        private SshClient client;
        private SshClient jumpClient;
        private ClientSession jumpSession;
        volatile ClientSession session;

        Upstream(WorkerInit init, WorkerEndpoint endpoint) {
            this.init = init;
            this.endpoint = endpoint;
        }

        void connect() throws ConnectFailure {
            Duration timeout = Duration.ofSeconds(init.timeoutSeconds > 0 ? init.timeoutSeconds : 15);
            String connectHost = init.host;
            int connectPort = init.port;
            try {
                if (init.jump != null) {
                    SshdSocketAddress local = openJump(timeout);
                    connectHost = local.getHostName();
                    connectPort = local.getPort();
                }
                client = newClient("target", init.host, init.port, "key".equals(init.auth), init.keyOnly);
                client.setSessionFactory(SshLivenessProbe.inboundCountingSessionFactory(client, inboundBytes));
                client.start();
                session = client.connect(init.username, connectHost, connectPort).verify(timeout).getSession();
                session.setKeyIdentityProvider(null);
                if (!"key".equals(init.auth) && init.password != null) {
                    session.addPasswordIdentity(init.password);
                }
                session.auth().verify(timeout);
                logger.info("Connected and authenticated");
            } catch (ConnectFailure failure) {
                throw failure;
            } catch (Exception e) {
                if (hostKeyRejected.get()) {
                    throw new ConnectFailure("hostkey", "host key rejected", e);
                }
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (message.contains("authentication") || message.contains("No more authentication methods")) {
                    throw new ConnectFailure("auth", "Authentication failed: " + message, e);
                }
                throw new ConnectFailure("network", message, e);
            }
        }

        private SshdSocketAddress openJump(Duration timeout) throws Exception {
            WorkerInit.Jump jump = init.jump;
            jumpClient = newClient("jump", jump.host, jump.port, "key".equals(jump.auth), false);
            jumpClient.start();
            try {
                jumpSession = jumpClient.connect(jump.username, jump.host, jump.port).verify(timeout).getSession();
                jumpSession.setKeyIdentityProvider(null);
                if (!"key".equals(jump.auth)) {
                    if (jump.password == null || jump.password.isEmpty()) {
                        throw new ConnectFailure("config", "Jump server password is not available.", null);
                    }
                    jumpSession.addPasswordIdentity(jump.password);
                }
                jumpSession.auth().verify(timeout);
            } catch (ConnectFailure failure) {
                throw failure;
            } catch (Exception e) {
                if (hostKeyRejected.get()) {
                    throw new ConnectFailure("hostkey", "Jump server host key was not accepted.", e);
                }
                throw new ConnectFailure("network", "Jump server connection failed: " + e.getMessage(), e);
            }
            return jumpSession.startLocalPortForwarding(new SshdSocketAddress("127.0.0.1", 0),
                new SshdSocketAddress(init.host, init.port));
        }

        /** A client that asks korTTY about host keys, prompts and (with {@code keyAuth}) signatures. */
        private SshClient newClient(String role, String host, int port, boolean keyAuth, boolean keyOnly) {
            SshClient c = SshClient.setUpDefaultClient();
            c.setKeyIdentityProvider(null);
            configureKeepAlive(c);
            c.setUserAuthFactories(keyAuth
                ? List.of(new UserAuthPublicKeyFactory(), new UserAuthKeyboardInteractiveFactory(),
                    new UserAuthPasswordFactory())
                : List.of(new UserAuthPasswordFactory(), new UserAuthKeyboardInteractiveFactory(),
                    new UserAuthPublicKeyFactory()));
            if (keyAuth) {
                c.setAgentFactory(RpcSshAgent.factory(endpoint, role));
            }
            c.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                JsonObject params = new JsonObject();
                params.addProperty("role", role);
                params.addProperty("host", host);
                params.addProperty("port", port);
                params.addProperty("key", PublicKeyEntry.toString(serverKey));
                try {
                    JsonObject answer = endpoint.call("hostKey.verify", params, RpcSshAgent.TIMEOUT_MILLIS);
                    boolean accepted = answer.has("accepted") && answer.get("accepted").getAsBoolean();
                    if (!accepted) {
                        hostKeyRejected.set(true);
                    }
                    return accepted;
                } catch (IOException e) {
                    logger.warn("Host key could not be confirmed: {}", e.getMessage());
                    hostKeyRejected.set(true);
                    return false;
                }
            });
            c.setUserInteraction(new UserInteraction() {
                @Override
                public boolean isInteractionAllowed(ClientSession clientSession) {
                    return true;
                }

                @Override
                public String[] interactive(ClientSession clientSession, String name, String instruction,
                                            String lang, String[] prompt, boolean[] echo) {
                    return askInteractive(role, name, instruction, prompt, echo);
                }

                @Override
                public String getUpdatedPassword(ClientSession clientSession, String prompt, String lang) {
                    return null;
                }
            });
            return c;
        }

        private String[] askInteractive(String role, String name, String instruction, String[] prompt, boolean[] echo) {
            if (prompt == null || prompt.length == 0) {
                return new String[0];
            }
            JsonObject params = new JsonObject();
            params.addProperty("role", role);
            params.addProperty("name", name);
            params.addProperty("instruction", instruction);
            JsonArray prompts = new JsonArray();
            JsonArray echoes = new JsonArray();
            for (int i = 0; i < prompt.length; i++) {
                prompts.add(prompt[i]);
                echoes.add(echo != null && i < echo.length && echo[i]);
            }
            params.add("prompts", prompts);
            params.add("echo", echoes);
            String[] answers = new String[prompt.length];
            java.util.Arrays.fill(answers, "");
            try {
                JsonObject result = endpoint.call("auth.interactive", params, RpcSshAgent.TIMEOUT_MILLIS);
                JsonArray replies = result.has("answers") ? result.getAsJsonArray("answers") : new JsonArray();
                for (int i = 0; i < answers.length && i < replies.size(); i++) {
                    answers[i] = replies.get(i).isJsonNull() ? "" : replies.get(i).getAsString();
                }
            } catch (IOException e) {
                logger.warn("Keyboard-interactive prompt could not be answered: {}", e.getMessage());
            }
            return answers;
        }

        private void configureKeepAlive(SshClient c) {
            if (!init.keepAliveEnabled) {
                CommonModuleProperties.SESSION_HEARTBEAT_TYPE.set(c, SessionHeartbeatController.HeartbeatType.NONE);
                CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.set(c, Duration.ZERO);
                CoreModuleProperties.SOCKET_KEEPALIVE.set(c, false);
                return;
            }
            int interval = Math.max(5, Math.min(init.keepAliveIntervalSeconds, 600));
            CommonModuleProperties.SESSION_HEARTBEAT_TYPE.set(c, SessionHeartbeatController.HeartbeatType.IGNORE);
            CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.set(c, Duration.ofSeconds(interval));
            CoreModuleProperties.SOCKET_KEEPALIVE.set(c, true);
        }

        /** Probes the server like a direct session does and closes the connection once it is dead. */
        void startLivenessProbe() {
            ClientSession probed = session;
            SshLivenessProbe.Transport transport = new SshLivenessProbe.Transport() {
                @Override
                public boolean isOpen() {
                    return probed.isOpen() && !probed.isClosing();
                }

                @Override
                public boolean probe() {
                    try {
                        Buffer buffer = probed.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST, 32);
                        buffer.putString("keepalive@kortty.de");
                        buffer.putBoolean(true);
                        probed.request("keepalive@kortty.de", buffer, 3_000);
                        return true;
                    } catch (IOException | RuntimeException e) {
                        return false;
                    }
                }

                @Override
                public long inboundBytes() {
                    return inboundBytes.get();
                }
            };
            Thread thread = new Thread(() -> {
                try {
                    SshLivenessProbe.Outcome outcome = new SshLivenessProbe(3_000, Thread::sleep)
                        .run(transport, () -> probed.isOpen());
                    if (outcome == SshLivenessProbe.Outcome.DEAD) {
                        logger.warn("Server connection is dead; closing it");
                        probed.close(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Worker-Liveness");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            closeQuietly(session);
            closeQuietly(jumpSession);
            stopQuietly(client);
            stopQuietly(jumpClient);
        }

        private static void closeQuietly(ClientSession s) {
            try {
                if (s != null) {
                    s.close(true);
                }
            } catch (RuntimeException ignored) {
                // Exiting anyway.
            }
        }

        private static void stopQuietly(SshClient c) {
            try {
                if (c != null) {
                    c.stop();
                }
            } catch (RuntimeException ignored) {
                // Exiting anyway.
            }
        }
    }
}
