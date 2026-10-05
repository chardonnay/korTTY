package de.kortty.core;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mosh connector that uses mosh4j release artifacts instead of native mosh-client.
 * Release jars are loaded dynamically so korTTY does not need compile-time mosh4j dependencies.
 */
public class Mosh4jTtyConnector implements TtyConnector, de.kortty.isolation.IsolationAware {

    private static final Logger logger = LoggerFactory.getLogger(Mosh4jTtyConnector.class);
    /**
     * Mosh is UTF-8 only by protocol: mosh-server and mosh-client require a UTF-8 locale, so the
     * terminal encoding setting does not apply (see {@link TerminalEncodingSupport#isUtf8Only}).
     */
    private static final Charset MOSH_CHARSET = StandardCharsets.UTF_8;
    private static final Pattern MOSH_CONNECT_PATTERN =
            Pattern.compile("MOSH CONNECT\\s+(\\d+)\\s+([A-Za-z0-9+/=]+)");

    /** Artifact version used in JAR file names and cache directories (e.g. mosh4j-core-2.0.2-arm64.jar). */
    private static final String MOSH4J_VERSION = "2.0.2";
    /**
     * GitHub release/git tag used for {@code gh release download} and the release download URL.
     * Note: since 2.0.1 the tag carries a leading "v" while the artifact version does not, so the
     * two are tracked separately (the 2.0.0 tag had no "v").
     */
    private static final String MOSH4J_RELEASE_TAG = "v" + MOSH4J_VERSION;

    /** Returns the mosh4j artifact version (e.g. for i18n messages). */
    public static String getMosh4jVersion() {
        return MOSH4J_VERSION;
    }

    private static final String DEP_BCPROV_VERSION = "1.84";
    private static final String DEP_BCPROV_JAR = "bcprov-jdk18on-" + DEP_BCPROV_VERSION + ".jar";
    private static final String DEP_BCPROV_URL =
            "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/"
                    + DEP_BCPROV_VERSION + "/" + DEP_BCPROV_JAR;
    // mosh4j 2.0.2's mosh4j-protocol generated DTOs are compiled against protobuf-java 4.35.1
    // (they reference com.google.protobuf.GeneratedFile, absent in 4.28.2). The runtime loaded here
    // must be at least that version and share its major: protobuf's generated code checks the pair
    // on class initialization and accepts a runtime NEWER than the gencode, never an older one. So
    // this may lead mosh4j's own <protobuf.version> — it must never trail it.
    private static final String DEP_PROTOBUF_VERSION = "4.36.2";
    private static final String DEP_PROTOBUF_JAR = "protobuf-java-" + DEP_PROTOBUF_VERSION + ".jar";
    private static final String DEP_PROTOBUF_URL =
            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/"
                    + DEP_PROTOBUF_VERSION + "/" + DEP_PROTOBUF_JAR;

    private static final int PIPE_BUFFER_CHARS = 1_048_576;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getenv("KORTTY_MOSH_DEBUG"));
    private static final String LOCAL_MOSH4J_REPO_ENV = "KORTTY_MOSH4J_LOCAL_REPO";
    private static final String MOSH4J_RELEASE_DIR_ENV = "KORTTY_MOSH4J_RELEASE_DIR";
    private static final String MOSH4J_SNAPSHOT_DIR_ENV = "KORTTY_MOSH4J_SNAPSHOT_DIR"; // legacy fallback

    private final ServerConnection connection;
    private final String password;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private SSHKeyManager sshKeyManager;
    private AccessReasonMemory accessReasonMemory;
    private char[] masterPassword;
    private DisconnectListener disconnectListener;
    private volatile Runnable onRecoveredCallback;
    private volatile Runnable onInterruptedCallback;

    /** The mosh4j session when it runs inside korTTY; null while not connected or in a worker. */
    private volatile Mosh4jEngine engine;

    /** The isolation this session is asked for; set before {@link #connect()}. */
    private volatile de.kortty.isolation.IsolationRequest isolationRequest = de.kortty.isolation.IsolationRequest.NONE;
    private volatile de.kortty.isolation.IsolationReport isolationReport = de.kortty.isolation.IsolationReport.NONE;
    /** The session worker running mosh4j, and korTTY's terminal channel on its endpoint; null in korTTY. */
    private volatile de.kortty.core.worker.SessionWorkerProcess worker;
    private volatile de.kortty.core.worker.WorkerLogin workerLogin;
    private volatile org.apache.sshd.client.channel.ChannelShell workerChannel;
    private volatile java.nio.file.Path sandboxSessionDirectory;
    /** korTTY's relay for a worker without a network of its own (the Linux sandbox), or null. */
    private volatile de.kortty.core.worker.NetworkRelay networkRelay;
    /** What the worker reported about the end of the session ({@code mosh.ended}), or null. */
    private volatile String workerEnd;
    private volatile String workerEndMessage;
    /** When the worker reported an interruption, or -1. */
    private volatile long workerInterruptedAtMs = -1L;

    private volatile PipedReader reader;
    private volatile PipedWriter writer;
    private volatile long totalCharsWrittenToPipe;
    private volatile long totalCharsReadFromPipe;
    private volatile int readLogCounter;
    /** True when this tab is the selected/focused terminal tab; used to avoid false "interrupted" when user switched away. */
    private volatile boolean terminalActive = true;

    public Mosh4jTtyConnector(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
    }

    public ServerConnection getConnection() {
        return connection;
    }

    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }

    /** Shares the owning tab's answered access reasons with the SSH bootstrap session. */
    public void setAccessReasonMemory(AccessReasonMemory accessReasonMemory) {
        this.accessReasonMemory = accessReasonMemory;
    }

    public void setDisconnectListener(DisconnectListener disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    /**
     * Sets a callback run when the session recovers from a transient network interruption
     * (so the UI can e.g. request focus to restore terminal input).
     */
    public void setOnRecoveredCallback(Runnable onRecoveredCallback) {
        this.onRecoveredCallback = onRecoveredCallback;
    }

    /**
     * Sets a callback run when the session enters a transient network interruption
     * (so the UI can show the status bar immediately without waiting for the next timer tick).
     */
    public void setOnInterruptedCallback(Runnable onInterruptedCallback) {
        this.onInterruptedCallback = onInterruptedCallback;
    }

    /**
     * Sets whether this terminal tab is the active (focused) one. When false, "no host bytes"
     * is not treated as connection interrupted, so switching to another tab does not show a false
     * "interrupted" status. When set to true, a short grace period is applied before applying
     * the no-host-bytes heuristic again.
     */
    public void setTerminalActive(boolean active) {
        this.terminalActive = active;
        Mosh4jEngine localEngine = engine;
        if (localEngine != null) {
            localEngine.setTerminalActive(active);
        }
        sendTerminalActive(active);
    }

    public static boolean isReleaseSupported() {
        String arch = mapArchSuffix(System.getProperty("os.arch"));
        Path releaseDir = resolveReleaseBaseDir().resolve("release-" + MOSH4J_VERSION + "-" + arch);
        return hasRequiredReleaseJars(releaseDir, arch) || commandExists("gh");
    }

    /**
     * Kept for compatibility with existing call sites.
     */
    @Deprecated
    public static boolean isSnapshotSupported() {
        return isReleaseSupported();
    }

    public boolean connect() throws SshTtyConnector.AuthenticationException {
        if (connection.getProtocol() != ConnectionProtocol.MOSH) {
            throw new IllegalStateException(i18n("mosh.mosh4j.protocolMismatch", i18n("protocol.mosh")));
        }
        // Refuse up front: the SSH bootstrap could hop through the bastion, but the subsequent
        // Mosh session is UDP straight to the target, which the jump server's TCP tunnel cannot
        // carry — the bootstrap would succeed and the session then stall with no data.
        if (JumpHostSupport.isActive(connection)) {
            throw new IllegalStateException(i18n("mosh.error.jumpServerUnsupported"));
        }
        try {
            String connectLine = sshBootstrapMoshServer();
            Matcher m = MOSH_CONNECT_PATTERN.matcher(connectLine);
            if (!m.find()) {
                throw new IOException(i18n("mosh.error.invalidConnectLine", connectLine));
            }
            int udpPort = Integer.parseInt(m.group(1));
            String key = m.group(2);

            if (isolationRequest.level() != de.kortty.isolation.IsolationLevel.NONE
                    && de.kortty.core.worker.SessionWorkerProcess.available()) {
                initIsolatedSession(connection.getHost(), udpPort, key);
            } else {
                initMosh4jSession(connection.getHost(), udpPort, key);
            }
            connected.set(true);
            logger.info("mosh4j {} session started for {}", MOSH4J_VERSION, connection.getDisplayName());
            return true;
        } catch (SshTtyConnector.AuthenticationException e) {
            close();
            throw e;
        } catch (Exception e) {
            logger.error("Failed to start mosh4j {} session for {}: {}", MOSH4J_VERSION, connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        }
    }

    private String sshBootstrapMoshServer() throws Exception {
        ServerConnection bootstrapConnection = resolveBootstrapConnection();
        SshTtyConnector bootstrap = new SshTtyConnector(bootstrapConnection, password);
        // The SSH login that starts mosh-server runs isolated as well.
        bootstrap.setIsolationRequest(isolationRequest);
        if (bootstrapConnection.getAuthMethod() == AuthMethod.PUBLIC_KEY && sshKeyManager != null) {
            bootstrap.setSSHKeyManager(sshKeyManager, masterPassword);
        }
        bootstrap.setAccessReasonMemory(accessReasonMemory);
        try {
            if (!bootstrap.connect()) {
                throw new IOException(i18n("mosh.error.sshBootstrapFailed"));
            }
            int timeoutSec = Math.max(5, connection.getConnectionTimeoutSeconds());
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(timeoutSec).toMillis();
            StringBuilder output = new StringBuilder();
            char[] buf = new char[8192];

            bootstrap.write("mosh-server new -s\n");
            while (System.currentTimeMillis() < deadline) {
                if (bootstrap.ready()) {
                    int count = bootstrap.read(buf, 0, buf.length);
                    if (count == -1) {
                        break;
                    }
                    if (count > 0) {
                        output.append(buf, 0, count);
                        Matcher matcher = MOSH_CONNECT_PATTERN.matcher(output);
                        if (matcher.find()) {
                            return matcher.group(0);
                        }
                    }
                } else {
                    Thread.sleep(40);
                }
            }
            // A partial (regex-unmatched) "MOSH CONNECT <port> <key>" line may sit in the buffered
            // output at timeout. Redact the session key before the text reaches the debug log or
            // the exception message, which callers log as well.
            String outputStr = output.toString()
                .replaceAll("MOSH CONNECT\\s+(\\d+)\\s+\\S+", "MOSH CONNECT $1 ***");
            if (logger.isDebugEnabled()) {
                logger.debug("Mosh handshake timeout after {} s. Partial server output ({} chars): {}", timeoutSec, outputStr.length(), outputStr);
            }
            int maxPreview = 500;
            String preview = outputStr.length() > maxPreview ? outputStr.substring(0, maxPreview) + "..." : outputStr;
            String escaped = sanitizePreviewForMessage(preview);
            String msg = i18n("mosh.error.handshakeTimeout", timeoutSec, output.length()) + i18n("mosh.error.partialOutput", escaped);
            throw new IOException(msg);
        } finally {
            bootstrap.close();
        }
    }

    private ServerConnection resolveBootstrapConnection() {
        if (connection.getAuthMethod() != AuthMethod.PUBLIC_KEY) {
            return connection;
        }
        boolean hasKeyMaterial = hasConfiguredKeyMaterial(connection, sshKeyManager != null);
        boolean hasPassword = password != null && !password.isBlank();
        if (hasKeyMaterial || !hasPassword) {
            return connection;
        }
        ServerConnection fallback = ServerConnection.copyForAuth(connection);
        fallback.setAuthMethod(AuthMethod.PASSWORD);
        logger.warn("MOSH bootstrap auth fallback to password (no key configured).");
        return fallback;
    }

    private static boolean hasConfiguredKeyMaterial(ServerConnection connection, boolean hasSshKeyManager) {
        boolean hasResolvableSshKeyId = hasSshKeyManager
                && connection.getSshKeyId() != null
                && !connection.getSshKeyId().isBlank();
        boolean hasPrivateKeyPath = connection.getPrivateKeyPath() != null
                && !connection.getPrivateKeyPath().isBlank();
        return hasResolvableSshKeyId || hasPrivateKeyPath;
    }

    private void initMosh4jSession(String host, int udpPort, String keyBase64) throws Exception {
        List<Path> jars = ensureReleaseClasspathJars();
        int cols = connection.getSettings() != null ? connection.getSettings().getTerminalColumns() : 80;
        int rows = connection.getSettings() != null ? connection.getSettings().getTerminalRows() : 24;
        writer = new PipedWriter();
        reader = new PipedReader(writer, PIPE_BUFFER_CHARS);
        totalCharsWrittenToPipe = 0;
        totalCharsReadFromPipe = 0;
        readLogCounter = 0;
        Mosh4jEngine started = new Mosh4jEngine(jars, host, udpPort, keyBase64, cols, rows, new Mosh4jEngine.Listener() {
            @Override
            public void output(String chunk) throws IOException {
                PipedWriter localWriter = writer;
                if (localWriter == null) {
                    throw new IOException("closed");
                }
                localWriter.write(chunk);
                localWriter.flush();
                totalCharsWrittenToPipe += chunk.length();
            }

            @Override
            public void interrupted() {
                notifyInterrupted();
            }

            @Override
            public void recovered() {
                notifyRecovered(1L);
            }

            @Override
            public void ended(Mosh4jEngine.End end, String message) {
                connected.set(false);
                if (disconnectListener != null) {
                    disconnectListener.onDisconnect(endReason(end, message), end == Mosh4jEngine.End.FAILED);
                }
            }
        });
        engine = started;
        started.setTerminalActive(terminalActive);
        started.start();
    }

    /**
     * Asks for {@code request}'s isolation: {@link de.kortty.isolation.IsolationLevel#PROCESS} and above
     * run the mosh4j session in a session worker. Call before {@link #connect()}.
     */
    public void setIsolationRequest(de.kortty.isolation.IsolationRequest request) {
        this.isolationRequest = request != null ? request : de.kortty.isolation.IsolationRequest.NONE;
    }

    @Override
    public de.kortty.isolation.IsolationReport isolationReport() {
        return isolationReport;
    }

    /**
     * Runs the mosh4j session in a session worker in Mosh mode: korTTY resolves (and if needed
     * downloads) the JARs, the worker loads them, talks UDP to the server and serves the terminal on
     * its loopback endpoint, in the sandbox when asked, which then reaches only the server's UDP port.
     */
    private void initIsolatedSession(String host, int udpPort, String keyBase64) throws Exception {
        List<Path> jars = ensureReleaseClasspathJars();
        int cols = connection.getSettings() != null ? connection.getSettings().getTerminalColumns() : 80;
        int rows = connection.getSettings() != null ? connection.getSettings().getTerminalRows() : 24;
        de.kortty.core.worker.WorkerInit init = new de.kortty.core.worker.WorkerInit();
        init.mode = "mosh";
        init.host = host;
        init.username = "mosh";
        init.moshPort = udpPort;
        init.moshKey = keyBase64;
        init.moshClasspath = jars.stream().map(path -> path.toAbsolutePath().toString()).toList();
        byte[] token = new byte[32];
        new java.security.SecureRandom().nextBytes(token);
        init.token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);

        List<Path> jarFolders = jars.stream().map(path -> path.toAbsolutePath().getParent()).distinct().toList();
        de.kortty.isolation.sandbox.LocalProcessSandbox.Prepared prepared;
        try {
            prepared = de.kortty.isolation.sandbox.LocalProcessSandbox.prepareWorker(isolationRequest,
                de.kortty.core.worker.SessionWorkerProcess.defaultCommand(), List.of(), List.of(udpPort), jarFolders);
        } catch (de.kortty.isolation.IsolationUnavailableException e) {
            throw new IOException(e.getMessage(), e);
        }
        sandboxSessionDirectory = prepared.sessionDirectory();
        if (prepared.networkRelay()) {
            // No network of its own: korTTY passes the datagrams to the Mosh server, and only there.
            networkRelay = new de.kortty.core.worker.NetworkRelay(prepared.sessionDirectory(), java.util.Set.of(),
                new InetSocketAddress(host, udpPort));
            networkRelay.start();
            init.netnsFolder = prepared.sessionDirectory().toString();
        }
        de.kortty.core.worker.SessionWorkerProcess started = de.kortty.core.worker.SessionWorkerProcess.start(
            prepared.command(), prepared.environment(), init,
            (method, params) -> {
                throw new IOException("a Mosh worker asks nothing: " + method);
            }, host);
        worker = started;
        started.setEventConsumer(this::onWorkerEvent);
        de.kortty.core.worker.SessionWorkerProcess.Ready ready = started.awaitReady(Duration.ofSeconds(30));
        if (networkRelay != null) {
            ready = new de.kortty.core.worker.SessionWorkerProcess.Ready(networkRelay.endpointPort(), ready.hostKey(),
                ready.pid());
        }
        workerLogin = de.kortty.core.worker.WorkerLogin.connect(ready, init.token);
        org.apache.sshd.client.channel.ChannelShell channel = workerLogin.session().createShellChannel();
        channel.setPtyType("xterm-256color");
        channel.setPtyColumns(cols > 0 ? cols : 80);
        channel.setPtyLines(rows > 0 ? rows : 24);
        channel.open().verify(Duration.ofSeconds(15));
        workerChannel = channel;
        isolationReport = prepared.report();
        started.describe(connection.getDisplayName(), isolationReport.state());
        sendTerminalActive(terminalActive);

        writer = new PipedWriter();
        reader = new PipedReader(writer, PIPE_BUFFER_CHARS);
        Thread pump = new Thread(() -> pumpWorkerOutput(channel), "MOSH4J-Worker-Output");
        pump.setDaemon(true);
        pump.start();
        logger.info("mosh4j session for {}:{} runs in worker process {}", host, udpPort, ready.pid());
    }

    /** Copies the worker's terminal output into the pipe korTTY reads, then reports how it ended. */
    private void pumpWorkerOutput(org.apache.sshd.client.channel.ChannelShell channel) {
        try (java.io.Reader in = new java.io.InputStreamReader(channel.getInvertedOut(), MOSH_CHARSET)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                PipedWriter localWriter = writer;
                if (localWriter == null) {
                    break;
                }
                localWriter.write(buffer, 0, count);
                localWriter.flush();
            }
        } catch (IOException e) {
            logger.debug("Mosh worker output ended: {}", e.getMessage());
        }
        boolean wasConnected = connected.getAndSet(false);
        if (!wasConnected || disconnectListener == null) {
            return;
        }
        String end = workerEnd;
        String reason;
        boolean wasError;
        if (end != null) {
            Mosh4jEngine.End kind = Mosh4jEngine.End.valueOf(end);
            reason = endReason(kind, workerEndMessage);
            wasError = kind == Mosh4jEngine.End.FAILED;
        } else {
            de.kortty.core.worker.SessionWorkerProcess current = worker;
            java.util.List<String> tail = current != null ? current.stderrTail() : java.util.List.of();
            reason = I18n.get("isolation.worker.crashed",
                current != null ? current.exitCode().orElse(-1) : -1, tail.isEmpty() ? "" : tail.get(tail.size() - 1));
            wasError = true;
        }
        disconnectListener.onDisconnect(reason, wasError);
    }

    private void onWorkerEvent(com.google.gson.JsonObject event) {
        String type = event.has("type") ? event.get("type").getAsString() : "";
        switch (type) {
            case "mosh.interrupted" -> {
                if (workerInterruptedAtMs < 0) {
                    workerInterruptedAtMs = System.currentTimeMillis();
                }
                notifyInterrupted();
            }
            case "mosh.recovered" -> {
                long was = workerInterruptedAtMs;
                workerInterruptedAtMs = -1L;
                notifyRecovered(was > 0 ? was : 1L);
            }
            case "mosh.ended" -> {
                workerEnd = event.has("end") ? event.get("end").getAsString() : Mosh4jEngine.End.ENDED.name();
                workerEndMessage = event.has("message") ? event.get("message").getAsString() : null;
            }
            default -> logger.debug("Mosh worker sent {}", type);
        }
    }

    private void sendTerminalActive(boolean active) {
        de.kortty.core.worker.SessionWorkerProcess current = worker;
        if (current == null) {
            return;
        }
        com.google.gson.JsonObject event = new com.google.gson.JsonObject();
        event.addProperty("type", "mosh.active");
        event.addProperty("active", active);
        try {
            current.send(event);
        } catch (IOException e) {
            logger.debug("Could not tell the Mosh worker about the tab: {}", e.getMessage());
        }
    }

    /** The disconnect message for how a session ended. */
    static String endReason(Mosh4jEngine.End end, String message) {
        return switch (end) {
            case REMOTE_LOGOUT -> i18n("mosh.mosh4j.remoteLogout");
            case FAILED -> i18n("mosh.mosh4j.frontendFailed", message != null ? message : "");
            case ENDED, STOPPED -> i18n("mosh.mosh4j.sessionEnded");
        };
    }

    private List<Path> ensureReleaseClasspathJars() throws Exception {
        List<Path> localBuildJars = findLocalBuildClasspathJars();
        if (!localBuildJars.isEmpty()) {
            if (DEBUG) {
                logger.info("Using local mosh4j build from {} ({})", System.getenv(LOCAL_MOSH4J_REPO_ENV), localBuildJars.size());
            }
            return withSharedDependencies(localBuildJars);
        }

        String arch = mapArchSuffix(System.getProperty("os.arch"));
        Path cacheBase = resolveReleaseBaseDir();
        Files.createDirectories(cacheBase);

        Path releaseDir = cacheBase.resolve("release-" + MOSH4J_VERSION + "-" + arch);
        if (!hasRequiredReleaseJars(releaseDir, arch)) {
            downloadRelease(cacheBase, arch);
        }
        if (!hasRequiredReleaseJars(releaseDir, arch)) {
            throw new IOException(i18n("mosh.mosh4j.releaseJarsNotFound", releaseDir));
        }

        Path depDir = cacheBase.resolve("deps");
        Files.createDirectories(depDir);

        List<Path> classpath = new ArrayList<>();
        classpath.add(releaseDir.resolve("mosh4j-protocol-" + MOSH4J_VERSION + "-" + arch + ".jar"));
        classpath.add(releaseDir.resolve("mosh4j-crypto-" + MOSH4J_VERSION + "-" + arch + ".jar"));
        classpath.add(releaseDir.resolve("mosh4j-transport-" + MOSH4J_VERSION + "-" + arch + ".jar"));
        classpath.add(releaseDir.resolve("mosh4j-terminal-" + MOSH4J_VERSION + "-" + arch + ".jar"));
        classpath.add(releaseDir.resolve("mosh4j-core-" + MOSH4J_VERSION + "-" + arch + ".jar"));
        addSharedDependencies(classpath, depDir);
        return classpath;
    }

    private List<Path> withSharedDependencies(List<Path> moduleJars) throws IOException {
        Path cacheBase = resolveReleaseBaseDir();
        Files.createDirectories(cacheBase);
        Path depDir = cacheBase.resolve("deps");
        Files.createDirectories(depDir);

        List<Path> classpath = new ArrayList<>(moduleJars);
        addSharedDependencies(classpath, depDir);
        return classpath;
    }

    private void addSharedDependencies(List<Path> classpath, Path depDir) throws IOException {
        // URLClassLoader is parent-first. The application already carries bcprov for SSH key
        // compatibility, so adding the same 8.5 MiB JAR to mosh4j's child classpath is redundant.
        // Retain the download only for unusual developer/standalone setups whose parent lacks BC.
        if (!parentProvidesBouncyCastle(getClass().getClassLoader())) {
            Path bcprovJar = depDir.resolve(DEP_BCPROV_JAR);
            downloadIfMissing(bcprovJar, DEP_BCPROV_URL);
            classpath.add(bcprovJar);
        }
        Path protobufJar = depDir.resolve(DEP_PROTOBUF_JAR);
        downloadIfMissing(protobufJar, DEP_PROTOBUF_URL);
        classpath.add(protobufJar);
    }

    static boolean parentProvidesBouncyCastle(ClassLoader parent) {
        if (parent == null) {
            return false;
        }
        try {
            Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider", false, parent);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private List<Path> findLocalBuildClasspathJars() {
        String repoPath = System.getenv(LOCAL_MOSH4J_REPO_ENV);
        if (repoPath == null || repoPath.isBlank()) {
            return List.of();
        }
        Path repoRoot = Path.of(repoPath.trim());
        String[] modules = {"protocol", "crypto", "transport", "terminal", "core"};
        List<Path> jars = new ArrayList<>(modules.length);
        for (String module : modules) {
            Path jar = resolveLocalModuleJar(repoRoot, module);
            if (!Files.isRegularFile(jar)) {
                return List.of();
            }
            jars.add(jar);
        }
        return jars;
    }

    /**
     * Base directory for mosh4j release JARs. Prefers bundled lib (jpackage app image)
     * so users do not need to download; then env; then ~/.kortty/mosh4j.
     */
    private static Path resolveReleaseBaseDir() {
        Path bundled = resolveBundledMosh4jBase();
        if (bundled != null) {
            return bundled;
        }
        String customDir = System.getenv(MOSH4J_RELEASE_DIR_ENV);
        if (customDir == null || customDir.isBlank()) {
            customDir = System.getenv(MOSH4J_SNAPSHOT_DIR_ENV);
        }
        if (customDir != null && !customDir.isBlank()) {
            return Path.of(customDir.trim());
        }
        return Path.of(System.getProperty("user.home"), ".kortty", "mosh4j");
    }

    /** If this app ships with mosh4j (e.g. jpackage release), return the mosh4j base path; else null. */
    private static Path resolveBundledMosh4jBase() {
        try {
            URL codeSource = Mosh4jTtyConnector.class.getProtectionDomain().getCodeSource().getLocation();
            if (codeSource == null) return null;
            Path jarPath;
            String urlStr = codeSource.toString();
            if (urlStr.startsWith("jar:")) {
                int end = urlStr.indexOf("!");
                String filePart = end > 0 ? urlStr.substring(4, end) : urlStr.substring(4);
                jarPath = Path.of(java.net.URI.create(filePart));
            } else {
                jarPath = Path.of(codeSource.toURI());
            }
            if (jarPath.getFileName() != null && jarPath.getFileName().toString().toLowerCase().endsWith(".jar")) {
                Path appLibDir = jarPath.getParent();
                if (appLibDir != null && Files.isDirectory(appLibDir)) {
                    Path mosh4jBase = appLibDir.resolve("mosh4j");
                    String arch = mapArchSuffix(System.getProperty("os.arch"));
                    Path releaseDir = mosh4jBase.resolve("release-" + MOSH4J_VERSION + "-" + arch);
                    if (hasRequiredReleaseJars(releaseDir, arch)) {
                        return mosh4jBase;
                    }
                }
            }
        } catch (Exception ignored) {
            // not running from a bundled layout
        }
        return null;
    }

    private static String mapArchSuffix(String osArchRaw) {
        String osArch = osArchRaw == null ? "" : osArchRaw.toLowerCase();
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        return "amd64";
    }

    private static boolean hasRequiredReleaseJars(Path releaseDir, String arch) {
        if (releaseDir == null) return false;
        return Files.isRegularFile(releaseDir.resolve("mosh4j-core-" + MOSH4J_VERSION + "-" + arch + ".jar"))
                && Files.isRegularFile(releaseDir.resolve("mosh4j-crypto-" + MOSH4J_VERSION + "-" + arch + ".jar"))
                && Files.isRegularFile(releaseDir.resolve("mosh4j-protocol-" + MOSH4J_VERSION + "-" + arch + ".jar"))
                && Files.isRegularFile(releaseDir.resolve("mosh4j-terminal-" + MOSH4J_VERSION + "-" + arch + ".jar"))
                && Files.isRegularFile(releaseDir.resolve("mosh4j-transport-" + MOSH4J_VERSION + "-" + arch + ".jar"));
    }

    private static void downloadRelease(Path cacheBase, String arch) throws Exception {
        Path releaseDir = cacheBase.resolve("release-" + MOSH4J_VERSION + "-" + arch);
        Files.createDirectories(releaseDir);
        if (!commandExists("gh")) {
            throw new IOException(i18n("mosh.mosh4j.ghNotFound"));
        }
        Process process = new ProcessBuilder(
                "gh", "release", "download", MOSH4J_RELEASE_TAG,
                "-R", "chardonnay/mosh4j",
                "--dir", releaseDir.toString(),
                "--pattern", "mosh4j-*-" + MOSH4J_VERSION + "-" + arch + ".jar"
        )
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            throw new IOException(i18n("mosh.mosh4j.downloadTimeout"));
        }
        if (process.exitValue() != 0) {
            throw new IOException(i18n("mosh.mosh4j.ghDownloadFailed", new String(output, StandardCharsets.UTF_8)));
        }
    }

    private static Path resolveLocalModuleJar(Path repoRoot, String module) {
        Path targetDir = repoRoot.resolve("mosh4j-" + module).resolve("target");
        Path releaseJar = targetDir.resolve("mosh4j-" + module + "-" + MOSH4J_VERSION + ".jar");
        if (Files.isRegularFile(releaseJar)) {
            return releaseJar;
        }
        // Fallback for older local builds.
        return targetDir.resolve("mosh4j-" + module + "-0.1.0-SNAPSHOT.jar");
    }

    private static void downloadIfMissing(Path targetFile, String url) throws IOException {
        if (Files.isRegularFile(targetFile)) {
            return;
        }
        Files.createDirectories(targetFile.getParent());
        logger.info("Downloading dependency: {}", targetFile.getFileName());
        try (InputStream in = java.net.URI.create(url).toURL().openStream();
             OutputStream out = Files.newOutputStream(targetFile)) {
            in.transferTo(out);
        }
    }

    private static boolean commandExists(String command) {
        if (command == null || !command.matches("[a-zA-Z0-9._-]+")) {
            return false;
        }
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-lc", "command -v " + command).start();
            boolean finished = p.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.getInputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    p.getOutputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    p.getErrorStream().close();
                } catch (Exception ignored) {
                }
                p.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        connected.set(false);
        org.apache.sshd.client.channel.ChannelShell localChannel = workerChannel;
        workerChannel = null;
        if (localChannel != null) {
            localChannel.close(false);
        }
        de.kortty.core.worker.WorkerLogin localLogin = workerLogin;
        workerLogin = null;
        if (localLogin != null) {
            localLogin.close();
        }
        de.kortty.core.worker.SessionWorkerProcess localWorker = worker;
        if (localWorker != null) {
            localWorker.close();
        }
        de.kortty.core.worker.NetworkRelay relay = networkRelay;
        networkRelay = null;
        if (relay != null) {
            relay.close();
        }
        de.kortty.isolation.sandbox.SandboxSupport.deleteSessionDirectory(sandboxSessionDirectory);
        sandboxSessionDirectory = null;

        Mosh4jEngine localEngine = engine;
        engine = null;
        if (localEngine != null) {
            localEngine.close();
        }

        PipedWriter localWriter = writer;
        writer = null;
        if (localWriter != null) {
            try {
                localWriter.close();
            } catch (IOException ignored) {
            }
        }

        PipedReader localReader = reader;
        reader = null;
        if (localReader != null) {
            try {
                localReader.close();
            } catch (IOException ignored) {
            }
        }

    }

    @Override
    public String getName() {
        return connection.getDisplayName() + " [" + i18n("mosh.mosh4j.nameSuffix", MOSH4J_VERSION) + "]";
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        PipedReader localReader = reader;
        if (!connected.get() || localReader == null) {
            return -1;
        }
        int count = localReader.read(buf, offset, length);
        if (count > 0) {
            totalCharsReadFromPipe += count;
            if (DEBUG && (readLogCounter < 5 || totalCharsReadFromPipe % 8192 < count)) {
                readLogCounter++;
                logger.info("MOSH4J read count={} totalRead={} requested={}",
                        count, totalCharsReadFromPipe, length);
            }
        }
        return count;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (!connected.get() || bytes == null || bytes.length == 0) {
            return;
        }
        org.apache.sshd.client.channel.ChannelShell localChannel = workerChannel;
        if (localChannel != null) {
            OutputStream toWorker = localChannel.getInvertedIn();
            toWorker.write(bytes);
            toWorker.flush();
            return;
        }
        Mosh4jEngine localEngine = engine;
        if (localEngine == null) {
            return;
        }
        try {
            localEngine.sendInput(bytes);
        } catch (IOException e) {
            throw new IOException(i18n("mosh.mosh4j.sendInputFailed"), e);
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null || string.isEmpty()) {
            return;
        }
        write(string.getBytes(MOSH_CHARSET));
    }

    @Override
    public boolean isConnected() {
        // A mosh session stays locally alive during transient network interruptions.
        // Returning false here blocks the terminal widget from forwarding user input,
        // which defeats mosh's ability to continue accepting and queueing keystrokes.
        return connected.get();
    }

    public boolean isNetworkInterrupted() {
        Mosh4jEngine localEngine = engine;
        if (worker != null) {
            return connected.get() && workerInterruptedAtMs > 0;
        }
        return connected.get() && localEngine != null && localEngine.isInterrupted();
    }

    public long getInterruptionStartedAtMs() {
        Mosh4jEngine localEngine = engine;
        if (worker != null) {
            return workerInterruptedAtMs;
        }
        return localEngine != null ? localEngine.interruptionStartedAtMs() : -1L;
    }

    private void notifyInterrupted() {
        Runnable cb = onInterruptedCallback;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                logger.warn("onInterruptedCallback failed: {}", e.getMessage());
            }
        }
    }

    private void notifyRecovered(long wasInterruptedAtMs) {
        if (wasInterruptedAtMs > 0) {
            Runnable cb = onRecoveredCallback;
            if (cb != null) {
                try {
                    cb.run();
                } catch (Exception e) {
                    logger.warn("onRecoveredCallback failed: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        org.apache.sshd.client.channel.ChannelShell localChannel = workerChannel;
        if (localChannel != null) {
            localChannel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 0L);
            return 0;
        }
        Mosh4jEngine localEngine = engine;
        if (localEngine != null) {
            localEngine.join();
        }
        return 0;
    }

    @Override
    public boolean ready() throws IOException {
        PipedReader localReader = reader;
        return connected.get() && localReader != null && localReader.ready();
    }

    @Override
    public void resize(TermSize termSize) {
        if (!connected.get() || termSize == null) {
            return;
        }
        org.apache.sshd.client.channel.ChannelShell localChannel = workerChannel;
        if (localChannel != null) {
            try {
                localChannel.sendWindowChange(termSize.getColumns(), termSize.getRows());
            } catch (IOException e) {
                logger.debug("Failed to send the window change to the Mosh worker: {}", e.getMessage());
            }
            return;
        }
        Mosh4jEngine localEngine = engine;
        if (localEngine != null) {
            localEngine.resize(termSize.getColumns(), termSize.getRows());
        }
    }

    /**
     * Sanitizes a preview string so it can be safely shown in an IOException message (no raw ESC/CSI/BEL or other control bytes).
     * Escapes ESC (U+001B), strips CSI sequences to a safe placeholder, and converts BEL (U+0007) and other
     * non-printable bytes to visible \\xHH escapes.
     */
    private static String sanitizePreviewForMessage(String preview) {
        if (preview == null) return "";
        StringBuilder sb = new StringBuilder(preview.length() * 2);
        for (int i = 0; i < preview.length(); i++) {
            char c = preview.charAt(i);
            if (c == '\u001B') {
                if (i + 1 < preview.length() && preview.charAt(i + 1) == '[') {
                    sb.append("\\x1B[CSI]");
                    i++;
                    while (i + 1 < preview.length()) {
                        char n = preview.charAt(i + 1);
                        if (n >= 0x40 && n <= 0x7E) {
                            i++;
                            break;
                        }
                        i++;
                    }
                } else {
                    sb.append("\\x1B");
                }
            } else if (c == '\u0007' || (c >= 0x00 && c <= 0x1F) || c == 0x7F) {
                sb.append(String.format("\\x%02X", (int) c));
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String i18n(String key, Object... args) {
        return LanguageManager.getInstance().getString(key, args);
    }
}
