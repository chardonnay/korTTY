package de.kortty.core;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
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
 * Mosh connector that uses mosh4j snapshot artifacts instead of native mosh-client.
 * Snapshot jars are loaded dynamically so korTTY does not need compile-time mosh4j dependencies.
 */
public class Mosh4jTtyConnector implements TtyConnector {

    private static final Logger logger = LoggerFactory.getLogger(Mosh4jTtyConnector.class);
    private static final Pattern MOSH_CONNECT_PATTERN =
            Pattern.compile("MOSH CONNECT\\s+(\\d+)\\s+([A-Za-z0-9+/=]+)");

    private static final String SNAPSHOT_RUN_ID = "22390926211";
    private static final String SNAPSHOT_COMMIT = "aafbe055e4fa0a13088d653d077cc6ba8cea59d3";

    private static final String DEP_BCPROV_URL =
            "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar";
    private static final String DEP_PROTOBUF_URL =
            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.28.2/protobuf-java-4.28.2.jar";

    private static final int PIPE_BUFFER_CHARS = 1_048_576;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getenv("KORTTY_MOSH_DEBUG"));
    private static final long KEEPALIVE_INTERVAL_MS = 2500L;
    private static final String LOCAL_MOSH4J_REPO_ENV = "KORTTY_MOSH4J_LOCAL_REPO";

    private final ServerConnection connection;
    private final String password;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;
    private DisconnectListener disconnectListener;

    private volatile URLClassLoader classLoader;
    private volatile Object frontend;
    private volatile Method frontendSendUserInput;
    private volatile Method frontendSendResize;
    private volatile Method frontendTakeRenderedOutput;
    private volatile Method frontendTakeHostBytes;
    private volatile Method frontendSendInitialWakeUp;
    private volatile Method frontendSendHeartbeat;
    private volatile Method frontendStart;
    private volatile Method frontendClose;
    private volatile Method frontendIsRunning;

    private volatile PipedReader reader;
    private volatile PipedWriter writer;
    private volatile Thread outputDrainThread;
    private volatile long connectStartedAtMs;
    private volatile long totalCharsWrittenToPipe;
    private volatile long totalCharsReadFromPipe;
    private volatile int readLogCounter;
    private volatile long interruptionStartedAtMs = -1L;
    private volatile long lastUserInputAtMs = -1L;
    private volatile long logoutRequestedAtMs = -1L;

    public Mosh4jTtyConnector(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
    }

    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }

    public void setDisconnectListener(DisconnectListener disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    public static boolean isSnapshotSupported() {
        String arch = mapArchSuffix(System.getProperty("os.arch"));
        Path snapshotDir = resolveSnapshotBaseDir().resolve("snapshot-" + SNAPSHOT_COMMIT + "-" + arch);
        return hasRequiredSnapshotJars(snapshotDir, arch) || commandExists("gh");
    }

    public boolean connect() throws SshTtyConnector.AuthenticationException {
        if (connection.getProtocol() != ConnectionProtocol.MOSH) {
            throw new IllegalStateException("Mosh4jTtyConnector requires protocol MOSH (mosh4j)");
        }
        try {
            String connectLine = sshBootstrapMoshServer();
            Matcher m = MOSH_CONNECT_PATTERN.matcher(connectLine);
            if (!m.find()) {
                throw new IOException("Invalid MOSH CONNECT line: " + connectLine);
            }
            int udpPort = Integer.parseInt(m.group(1));
            String key = m.group(2);

            initMosh4jSession(connection.getHost(), udpPort, key);
            connected.set(true);
            connectStartedAtMs = System.currentTimeMillis();
            interruptionStartedAtMs = -1L;
            lastUserInputAtMs = -1L;
            logoutRequestedAtMs = -1L;
            startOutputDrainLoop();
            logger.info("mosh4j snapshot session started for {}", connection.getDisplayName());
            return true;
        } catch (SshTtyConnector.AuthenticationException e) {
            close();
            throw e;
        } catch (Exception e) {
            logger.error("Failed to start mosh4j snapshot session for {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        }
    }

    private String sshBootstrapMoshServer() throws Exception {
        SshTtyConnector bootstrap = new SshTtyConnector(connection, password);
        if (connection.getAuthMethod() == AuthMethod.PUBLIC_KEY && sshKeyManager != null) {
            bootstrap.setSSHKeyManager(sshKeyManager, masterPassword);
        }
        try {
            if (!bootstrap.connect()) {
                throw new IOException("SSH bootstrap connection failed");
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
            throw new IOException("mosh-server handshake timed out. Output: " + output);
        } finally {
            bootstrap.close();
        }
    }

    private void initMosh4jSession(String host, int udpPort, String keyBase64) throws Exception {
        List<Path> jars = ensureSnapshotClasspathJars();
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        classLoader = new URLClassLoader(urls, getClass().getClassLoader());

        Class<?> moshKeyClass = classLoader.loadClass("org.mosh4j.crypto.MoshKey");
        Method fromBase64 = moshKeyClass.getMethod("fromBase64", String.class);
        Object moshKey = fromBase64.invoke(null, keyBase64);

        Class<?> sessionClass = classLoader.loadClass("org.mosh4j.core.MoshClientSession");
        Constructor<?> ctor = sessionClass.getConstructor(InetSocketAddress.class, moshKeyClass, int.class, int.class);

        int cols = connection.getSettings() != null ? connection.getSettings().getTerminalColumns() : 80;
        int rows = connection.getSettings() != null ? connection.getSettings().getTerminalRows() : 24;
        if (cols <= 0) cols = 80;
        if (rows <= 0) rows = 24;

        Object session = ctor.newInstance(new InetSocketAddress(host, udpPort), moshKey, cols, rows);

        Class<?> frontendClass = classLoader.loadClass("org.mosh4j.core.MoshTerminalFrontend");
        Constructor<?> frontendCtor = frontendClass.getConstructor(sessionClass);
        frontend = frontendCtor.newInstance(session);

        frontendSendUserInput = frontendClass.getMethod("sendUserInput", byte[].class);
        frontendSendResize = frontendClass.getMethod("sendResize", int.class, int.class);
        frontendTakeRenderedOutput = frontendClass.getMethod("takeRenderedOutput", long.class);
        try {
            frontendTakeHostBytes = frontendClass.getMethod("takeHostBytes", long.class);
        } catch (NoSuchMethodException ignored) {
            frontendTakeHostBytes = null;
        }
        frontendSendInitialWakeUp = frontendClass.getMethod("sendInitialWakeUp");
        try {
            frontendSendHeartbeat = frontendClass.getMethod("sendHeartbeat");
        } catch (NoSuchMethodException ignored) {
            frontendSendHeartbeat = null;
        }
        frontendStart = frontendClass.getMethod("start");
        frontendClose = frontendClass.getMethod("close");
        frontendIsRunning = frontendClass.getMethod("isRunning");

        frontendSendInitialWakeUp.invoke(frontend);
        frontendStart.invoke(frontend);
        // Native mosh-client sends an early resize; do the same to make sure
        // server-side PTY state is initialized before first prompt rendering.
        frontendSendResize.invoke(frontend, cols, rows);

        writer = new PipedWriter();
        reader = new PipedReader(writer, PIPE_BUFFER_CHARS);
        totalCharsWrittenToPipe = 0;
        totalCharsReadFromPipe = 0;
        readLogCounter = 0;
    }

    private List<Path> ensureSnapshotClasspathJars() throws Exception {
        List<Path> localBuildJars = findLocalBuildClasspathJars();
        if (!localBuildJars.isEmpty()) {
            if (DEBUG) {
                logger.info("Using local mosh4j build from {} ({})", System.getenv(LOCAL_MOSH4J_REPO_ENV), localBuildJars.size());
            }
            return withSharedDependencies(localBuildJars);
        }

        String arch = mapArchSuffix(System.getProperty("os.arch"));
        Path cacheBase = resolveSnapshotBaseDir();
        Files.createDirectories(cacheBase);

        Path snapshotDir = cacheBase.resolve("snapshot-" + SNAPSHOT_COMMIT + "-" + arch);
        if (!hasRequiredSnapshotJars(snapshotDir, arch)) {
            downloadSnapshot(cacheBase);
        }
        if (!hasRequiredSnapshotJars(snapshotDir, arch)) {
            throw new IOException("mosh4j snapshot jars not found in " + snapshotDir);
        }

        Path depDir = cacheBase.resolve("deps");
        Files.createDirectories(depDir);
        Path bcprovJar = depDir.resolve("bcprov-jdk18on-1.78.1.jar");
        Path protobufJar = depDir.resolve("protobuf-java-4.28.2.jar");
        downloadIfMissing(bcprovJar, DEP_BCPROV_URL);
        downloadIfMissing(protobufJar, DEP_PROTOBUF_URL);

        List<Path> classpath = new ArrayList<>();
        classpath.add(snapshotDir.resolve("mosh4j-protocol-0.1.0-SNAPSHOT-" + arch + ".jar"));
        classpath.add(snapshotDir.resolve("mosh4j-crypto-0.1.0-SNAPSHOT-" + arch + ".jar"));
        classpath.add(snapshotDir.resolve("mosh4j-transport-0.1.0-SNAPSHOT-" + arch + ".jar"));
        classpath.add(snapshotDir.resolve("mosh4j-terminal-0.1.0-SNAPSHOT-" + arch + ".jar"));
        classpath.add(snapshotDir.resolve("mosh4j-core-0.1.0-SNAPSHOT-" + arch + ".jar"));
        classpath.add(bcprovJar);
        classpath.add(protobufJar);
        return classpath;
    }

    private List<Path> withSharedDependencies(List<Path> moduleJars) throws IOException {
        Path cacheBase = resolveSnapshotBaseDir();
        Files.createDirectories(cacheBase);
        Path depDir = cacheBase.resolve("deps");
        Files.createDirectories(depDir);
        Path bcprovJar = depDir.resolve("bcprov-jdk18on-1.78.1.jar");
        Path protobufJar = depDir.resolve("protobuf-java-4.28.2.jar");
        downloadIfMissing(bcprovJar, DEP_BCPROV_URL);
        downloadIfMissing(protobufJar, DEP_PROTOBUF_URL);

        List<Path> classpath = new ArrayList<>(moduleJars);
        classpath.add(bcprovJar);
        classpath.add(protobufJar);
        return classpath;
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
            Path jar = repoRoot.resolve("mosh4j-" + module)
                    .resolve("target")
                    .resolve("mosh4j-" + module + "-0.1.0-SNAPSHOT.jar");
            if (!Files.isRegularFile(jar)) {
                return List.of();
            }
            jars.add(jar);
        }
        return jars;
    }

    private static Path resolveSnapshotBaseDir() {
        String customDir = System.getenv("KORTTY_MOSH4J_SNAPSHOT_DIR");
        if (customDir != null && !customDir.isBlank()) {
            return Path.of(customDir.trim());
        }
        return Path.of(System.getProperty("user.home"), ".kortty", "mosh4j", "snapshot-" + SNAPSHOT_RUN_ID);
    }

    private static String mapArchSuffix(String osArchRaw) {
        String osArch = osArchRaw == null ? "" : osArchRaw.toLowerCase();
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        return "amd64";
    }

    private static boolean hasRequiredSnapshotJars(Path snapshotDir, String arch) {
        if (snapshotDir == null) return false;
        return Files.isRegularFile(snapshotDir.resolve("mosh4j-core-0.1.0-SNAPSHOT-" + arch + ".jar"))
                && Files.isRegularFile(snapshotDir.resolve("mosh4j-crypto-0.1.0-SNAPSHOT-" + arch + ".jar"))
                && Files.isRegularFile(snapshotDir.resolve("mosh4j-protocol-0.1.0-SNAPSHOT-" + arch + ".jar"))
                && Files.isRegularFile(snapshotDir.resolve("mosh4j-terminal-0.1.0-SNAPSHOT-" + arch + ".jar"))
                && Files.isRegularFile(snapshotDir.resolve("mosh4j-transport-0.1.0-SNAPSHOT-" + arch + ".jar"));
    }

    private static void downloadSnapshot(Path cacheBase) throws Exception {
        if (!commandExists("gh")) {
            throw new IOException("GitHub CLI 'gh' not found. Install gh or provide KORTTY_MOSH4J_SNAPSHOT_DIR.");
        }
        Process process = new ProcessBuilder(
                "gh", "run", "download", SNAPSHOT_RUN_ID,
                "-R", "chardonnay/mosh4j",
                "--dir", cacheBase.toString()
        )
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            throw new IOException("Timed out while downloading mosh4j snapshot with gh");
        }
        if (process.exitValue() != 0) {
            throw new IOException("gh run download failed: " + new String(output, StandardCharsets.UTF_8));
        }
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

    private void startOutputDrainLoop() {
        outputDrainThread = new Thread(() -> {
            String disconnectReason = "Mosh4j session ended";
            boolean wasError = false;
            long lastHostBytesAt = System.currentTimeMillis();
            long lastKeepaliveAt = 0;
            boolean promptNudgeSent = false;
            try {
                while (connected.get()) {
                    try {
                        if (!isFrontendRunning()) {
                            if (interruptionStartedAtMs < 0) {
                                interruptionStartedAtMs = System.currentTimeMillis();
                            }
                            // Network glitches should not kill the mosh tab; try to revive
                            // the frontend receive loop and continue.
                            frontendStart.invoke(frontend);
                            Thread.sleep(100);
                            continue;
                        }
                        if (interruptionStartedAtMs > 0) {
                            interruptionStartedAtMs = -1L;
                        }

                        if (frontendTakeHostBytes != null) {
                            byte[] hostBytes = (byte[]) frontendTakeHostBytes.invoke(frontend, 250L);
                            if (hostBytes != null && hostBytes.length > 0) {
                                String chunk = new String(hostBytes, StandardCharsets.UTF_8);
                                writer.write(chunk);
                                writer.flush();
                                totalCharsWrittenToPipe += chunk.length();
                                lastHostBytesAt = System.currentTimeMillis();
                                interruptionStartedAtMs = -1L;
                                // Any fresh server output confirms the path is healthy again.
                                lastUserInputAtMs = -1L;
                                if (DEBUG) {
                                    String preview = chunk.replace("\u001B", "<ESC>")
                                            .replace("\r", "<CR>")
                                            .replace("\n", "<LF>");
                                    if (preview.length() > 220) {
                                        preview = preview.substring(0, 220) + "...";
                                    }
                                    logger.info("MOSH4J host-bytes chars={} totalWritten={} sinceConnectMs={} preview={}",
                                            chunk.length(), totalCharsWrittenToPipe,
                                            System.currentTimeMillis() - connectStartedAtMs, preview);
                                }
                                if (!promptNudgeSent) {
                                    frontendSendUserInput.invoke(frontend, (Object) "\r".getBytes(StandardCharsets.UTF_8));
                                    promptNudgeSent = true;
                                    if (DEBUG) {
                                        logger.info("MOSH4J prompt nudge sent (CR)");
                                    }
                                }
                                continue;
                            }
                            long now = System.currentTimeMillis();
                            if (lastUserInputAtMs > 0 && lastUserInputAtMs > lastHostBytesAt
                                    && now - lastUserInputAtMs >= KEEPALIVE_INTERVAL_MS
                                    && interruptionStartedAtMs < 0) {
                                interruptionStartedAtMs = now;
                            }
                            // Keep mosh session active with protocol heartbeat (no injected bytes).
                            if (now - lastHostBytesAt >= KEEPALIVE_INTERVAL_MS
                                    && now - lastKeepaliveAt >= KEEPALIVE_INTERVAL_MS) {
                                if (frontendSendHeartbeat != null) {
                                    frontendSendHeartbeat.invoke(frontend);
                                } else {
                                    frontendSendUserInput.invoke(frontend, (Object) new byte[]{0});
                                }
                                lastKeepaliveAt = now;
                                if (DEBUG) {
                                    logger.info("MOSH4J keepalive heartbeat sent");
                                }
                            }
                            continue;
                        }

                        // Fallback for older frontend builds that don't expose raw host bytes.
                        String frame = (String) frontendTakeRenderedOutput.invoke(frontend, 250L);
                        if (frame == null || frame.isEmpty()) {
                            continue;
                        }
                        writer.write(frame);
                        writer.flush();
                        totalCharsWrittenToPipe += frame.length();
                        if (DEBUG) {
                            String preview = frame.replace("\u001B", "<ESC>")
                                    .replace("\r", "<CR>")
                                    .replace("\n", "<LF>");
                            if (preview.length() > 220) {
                                preview = preview.substring(0, 220) + "...";
                            }
                            logger.info("MOSH4J frontend frame chars={} totalWritten={} sinceConnectMs={} preview={}",
                                    frame.length(), totalCharsWrittenToPipe,
                                    System.currentTimeMillis() - connectStartedAtMs, preview);
                        }
                    } catch (Exception loopError) {
                        Throwable cause = loopError instanceof InvocationTargetException ite ? ite.getCause() : loopError;
                        if (cause instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (interruptionStartedAtMs < 0) {
                            interruptionStartedAtMs = System.currentTimeMillis();
                        }
                        if (DEBUG) {
                            logger.info("MOSH4J transient frontend loop issue: {}", cause != null ? cause.getMessage() : loopError.getMessage());
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
                if (cause instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    wasError = false;
                    disconnectReason = "Mosh4j frontend loop stopped";
                } else {
                    wasError = true;
                    disconnectReason = "Mosh4j frontend loop failed: " + e.getMessage();
                    logger.warn(disconnectReason, e);
                }
            } finally {
                if (!wasError && logoutRequestedAtMs > 0) {
                    long now = System.currentTimeMillis();
                    // If Ctrl+D was sent recently, classify as remote logout so UI can close tab.
                    if (now - logoutRequestedAtMs <= 5000L) {
                        disconnectReason = "Mosh4j remote logout";
                    }
                }
                connected.set(false);
                // Ensure transport is closed even when the UI keeps the tab open.
                // This avoids leaving a detached local mosh client state behind.
                Object localFrontend = frontend;
                frontend = null;
                if (localFrontend != null && frontendClose != null) {
                    try {
                        frontendClose.invoke(localFrontend);
                    } catch (Exception ignored) {
                    }
                }
                if (disconnectListener != null) {
                    disconnectListener.onDisconnect(disconnectReason, wasError);
                }
            }
        }, "MOSH4J-Frontend-" + connection.getDisplayName());
        outputDrainThread.setDaemon(true);
        outputDrainThread.start();
    }

    private boolean isFrontendRunning() {
        try {
            Object running = frontendIsRunning.invoke(frontend);
            return running instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
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
        interruptionStartedAtMs = -1L;
        lastUserInputAtMs = -1L;
        logoutRequestedAtMs = -1L;

        Thread localOutputThread = outputDrainThread;
        outputDrainThread = null;
        if (localOutputThread != null) {
            localOutputThread.interrupt();
        }

        Object localFrontend = frontend;
        frontend = null;
        if (localFrontend != null && frontendClose != null) {
            try {
                frontendClose.invoke(localFrontend);
            } catch (Exception ignored) {
            }
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

        URLClassLoader localLoader = classLoader;
        classLoader = null;
        if (localLoader != null) {
            try {
                localLoader.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public String getName() {
        return connection.getDisplayName() + " [MOSH mosh4j-snapshot]";
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
        lastUserInputAtMs = System.currentTimeMillis();
        for (byte b : bytes) {
            if (b == 0x04) { // Ctrl+D / EOT
                logoutRequestedAtMs = lastUserInputAtMs;
                break;
            }
        }
        Object localFrontend = frontend;
        Method localSend = frontendSendUserInput;
        if (localFrontend == null || localSend == null) {
            return;
        }
        try {
            localSend.invoke(localFrontend, (Object) bytes);
        } catch (Exception e) {
            throw new IOException("Failed to send input to mosh4j", e);
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null || string.isEmpty()) {
            return;
        }
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connected.get() && isFrontendRunning();
    }

    public boolean isNetworkInterrupted() {
        return connected.get() && interruptionStartedAtMs > 0;
    }

    public long getInterruptionStartedAtMs() {
        return interruptionStartedAtMs;
    }

    @Override
    public int waitFor() throws InterruptedException {
        Thread localThread = outputDrainThread;
        if (localThread != null) {
            localThread.join();
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
        Object localFrontend = frontend;
        Method localResize = frontendSendResize;
        if (localFrontend == null || localResize == null) {
            return;
        }
        try {
            localResize.invoke(localFrontend, termSize.getColumns(), termSize.getRows());
        } catch (Exception e) {
            logger.debug("Failed to send mosh4j resize: {}", e.getMessage());
        }
    }
}
