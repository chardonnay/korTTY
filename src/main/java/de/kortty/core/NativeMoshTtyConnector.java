package de.kortty.core;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mosh connector that performs the SSH bootstrap via korTTY's SshTtyConnector,
 * then hands off to the native mosh-client binary running inside a pty4j PTY.
 * This provides full terminal emulation including resize support.
 */
public class NativeMoshTtyConnector implements TtyConnector {

    private static final Logger logger = LoggerFactory.getLogger(NativeMoshTtyConnector.class);
    private static final Pattern MOSH_CONNECT_PATTERN =
            Pattern.compile("MOSH CONNECT\\s+(\\d+)\\s+([A-Za-z0-9+/=]+)");

    private final ServerConnection connection;
    private final String password;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;
    private DisconnectListener disconnectListener;

    private volatile PtyProcess ptyProcess;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;
    private volatile InputStreamReader reader;
    private volatile Thread monitorThread;

    public NativeMoshTtyConnector(ServerConnection connection, String password) {
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

    public static boolean isNativeMoshAvailable() {
        return commandExists("mosh-client");
    }

    public boolean connect() throws SshTtyConnector.AuthenticationException {
        if (connection.getProtocol() != ConnectionProtocol.MOSH_CLIENT) {
            throw new IllegalStateException(i18n("mosh.native.protocolMismatch", i18n("protocol.moshClient")));
        }
        try {
            logger.info("Starting native MOSH for {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());

            String connectLine = sshBootstrapMoshServer();
            Matcher m = MOSH_CONNECT_PATTERN.matcher(connectLine);
            if (!m.find()) {
                throw new IOException(i18n("mosh.error.invalidConnectLine", connectLine));
            }
            int udpPort = Integer.parseInt(m.group(1));
            String key = m.group(2);
            logger.info("MOSH bootstrap OK: port={} keyLen={}", udpPort, key.length());

            startMoshClient(connection.getHost(), udpPort, key);
            connected.set(true);
            startMonitorThread();
            logger.info("Native MOSH client started for {}", connection.getDisplayName());
            return true;
        } catch (SshTtyConnector.AuthenticationException e) {
            close();
            throw e;
        } catch (Exception e) {
            logger.error("Failed to start native MOSH for {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        }
    }

    private String sshBootstrapMoshServer() throws Exception {
        ServerConnection bootstrapConnection = resolveBootstrapConnection();
        SshTtyConnector bootstrap = new SshTtyConnector(bootstrapConnection, password);
        if (bootstrapConnection.getAuthMethod() == AuthMethod.PUBLIC_KEY && sshKeyManager != null) {
            bootstrap.setSSHKeyManager(sshKeyManager, masterPassword);
        }
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
            throw new IOException(i18n("mosh.error.handshakeTimeout", timeoutSec, output.length()));
        } finally {
            bootstrap.close();
        }
    }

    private ServerConnection resolveBootstrapConnection() {
        if (connection.getAuthMethod() != AuthMethod.PUBLIC_KEY) {
            return connection;
        }
        boolean hasKeyMaterial = hasConfiguredKeyMaterial(connection);
        boolean hasPassword = password != null && !password.isBlank();
        if (hasKeyMaterial) {
            return connection;
        }
        if (!hasPassword) {
            logger.warn("MOSH bootstrap keeps selected authentication method (no key material configured, no fallback password).");
            return connection;
        }
        ServerConnection fallback = ServerConnection.copyForAuth(connection);
        fallback.setAuthMethod(AuthMethod.PASSWORD);
        logger.warn("MOSH bootstrap auth fallback to password (no key configured).");
        return fallback;
    }

    private static boolean hasConfiguredKeyMaterial(ServerConnection connection) {
        return connection.getSshKeyId() != null && !connection.getSshKeyId().isBlank()
                || connection.getPrivateKeyPath() != null && !connection.getPrivateKeyPath().isBlank();
    }

    private void startMoshClient(String host, int port, String key) throws IOException {
        int cols = connection.getSettings().getTerminalColumns();
        int rows = connection.getSettings().getTerminalRows();
        if (cols <= 0) cols = 80;
        if (rows <= 0) rows = 24;

        String moshClientPath = findCommand("mosh-client");
        if (moshClientPath == null) {
            throw new IOException(i18n("mosh.native.clientNotFoundInPath"));
        }

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("MOSH_KEY", key);
        env.put("TERM", "xterm-256color");
        if (env.get("LANG") == null || env.get("LANG").isBlank()) {
            env.put("LANG", "en_US.UTF-8");
        }

        String[] command = {moshClientPath, host, String.valueOf(port)};

        logger.debug("Starting mosh-client via pty4j ({}x{}) cmd={}", cols, rows, String.join(" ", command));

        ptyProcess = new PtyProcessBuilder(command)
                .setEnvironment(env)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setConsole(false)
                .start();

        inputStream = ptyProcess.getInputStream();
        outputStream = ptyProcess.getOutputStream();
        reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
    }

    private void startMonitorThread() {
        final PtyProcess localPty = ptyProcess;
        if (localPty == null) {
            connected.set(false);
            return;
        }
        monitorThread = new Thread(() -> {
            try {
                int exitCode = localPty.waitFor();
                connected.set(false);
                if (disconnectListener != null) {
                    boolean wasError = exitCode != 0;
                    String reason = wasError
                            ? i18n("mosh.native.disconnectExitCode", exitCode)
                            : i18n("mosh.native.disconnectNormal");
                    disconnectListener.onDisconnect(reason, wasError);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.warn("Monitor thread error, notifying disconnect", t);
                connected.set(false);
                if (disconnectListener != null) {
                    String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    disconnectListener.onDisconnect(safeI18nFallback("mosh.native.disconnectError", detail), true);
                }
            }
        }, "MOSH-Native-Monitor-" + connection.getDisplayName());
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private static boolean commandExists(String command) {
        return findCommand(command) != null;
    }

    private static String findCommand(String command) {
        if (command == null || !command.matches("[a-zA-Z0-9._-]+")) {
            return null;
        }
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-lc", "command -v " + command).start();
            boolean finished = p.waitFor(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            }
            if (p.exitValue() == 0) {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (Exception ignored) {}
                try { p.getOutputStream().close(); } catch (Exception ignored) {}
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
                p.destroyForcibly();
            }
        }
        return null;
    }

    @Override
    public void close() {
        connected.set(false);

        InputStreamReader localReader = reader;
        InputStream localIn = inputStream;
        OutputStream localOut = outputStream;
        reader = null;
        inputStream = null;
        outputStream = null;

        if (localReader != null) {
            try { localReader.close(); } catch (IOException ignored) {}
        }
        if (localIn != null) {
            try { localIn.close(); } catch (IOException ignored) {}
        }
        if (localOut != null) {
            try { localOut.close(); } catch (IOException ignored) {}
        }

        Thread localMonitor = monitorThread;
        monitorThread = null;
        if (localMonitor != null) {
            localMonitor.interrupt();
        }

        PtyProcess localPty = ptyProcess;
        ptyProcess = null;
        if (localPty != null) {
            localPty.destroy();
            try {
                if (!localPty.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    localPty.destroyForcibly();
                    localPty.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                localPty.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String getName() {
        return connection.getDisplayName() + " [" + i18n("mosh.native.nameSuffix") + "]";
    }

    private static String i18n(String key, Object... args) {
        LanguageManager lm = LanguageManager.getInstance();
        if (lm == null) {
            return formatFallback(key, args);
        }
        String s = lm.getString(key, args);
        if (s != null && !s.isEmpty()) {
            return s;
        }
        return formatFallback(key, args);
    }

    private static String formatFallback(String key, Object... args) {
        if (args == null || args.length == 0) {
            return key;
        }
        try {
            StringBuilder sb = new StringBuilder(key);
            for (int i = 0; i < args.length; i++) {
                sb.append(" ").append(args[i]);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return key;
        }
    }

    /** Null-safe i18n for use in catch blocks where i18n might throw. */
    private static String safeI18nFallback(String key, Object... args) {
        try {
            LanguageManager lm = LanguageManager.getInstance();
            if (lm != null) {
                String s = args == null || args.length == 0 ? lm.getString(key) : lm.getString(key, args);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return "Connection ended";
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        InputStreamReader localReader = reader;
        if (!connected.get() || localReader == null) {
            return -1;
        }
        return localReader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        OutputStream localOut = outputStream;
        if (connected.get() && localOut != null) {
            localOut.write(bytes);
            localOut.flush();
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null) return;
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        PtyProcess localPty = ptyProcess;
        return connected.get() && localPty != null && localPty.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        PtyProcess p = ptyProcess;
        if (p == null) return 0;
        return p.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        InputStream localIn = inputStream;
        return connected.get() && localIn != null && localIn.available() > 0;
    }

    @Override
    public void resize(TermSize termSize) {
        PtyProcess localPty = ptyProcess;
        if (connected.get() && localPty != null && localPty.isAlive()) {
            localPty.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
        }
    }
}
