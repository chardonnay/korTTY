package de.kortty.core;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
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

    private PtyProcess ptyProcess;
    private InputStream inputStream;
    private OutputStream outputStream;
    private InputStreamReader reader;
    private Thread monitorThread;

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
        if (connection.getProtocol() != ConnectionProtocol.MOSH) {
            throw new IllegalStateException("NativeMoshTtyConnector requires protocol MOSH");
        }
        try {
            logger.info("Starting native MOSH for {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());

            String connectLine = sshBootstrapMoshServer();
            Matcher m = MOSH_CONNECT_PATTERN.matcher(connectLine);
            if (!m.find()) {
                throw new IOException("Invalid MOSH CONNECT line: " + connectLine);
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
        SshTtyConnector bootstrap = new SshTtyConnector(connection, password);
        if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY && sshKeyManager != null) {
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

    private void startMoshClient(String host, int port, String key) throws IOException {
        int cols = connection.getSettings().getTerminalColumns();
        int rows = connection.getSettings().getTerminalRows();
        if (cols <= 0) cols = 80;
        if (rows <= 0) rows = 24;

        String moshClientPath = findCommand("mosh-client");
        if (moshClientPath == null) {
            throw new IOException("mosh-client binary not found in PATH");
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
        monitorThread = new Thread(() -> {
            try {
                int exitCode = ptyProcess.waitFor();
                connected.set(false);
                if (disconnectListener != null) {
                    boolean wasError = exitCode != 0;
                    String reason = wasError
                            ? "Native mosh-client exited with code " + exitCode
                            : "Native mosh-client ended normally";
                    disconnectListener.onDisconnect(reason, wasError);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "MOSH-Native-Monitor-" + connection.getDisplayName());
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private static boolean commandExists(String command) {
        return findCommand(command) != null;
    }

    private static String findCommand(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-lc", "command -v " + command).start();
            boolean finished = p.waitFor(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (finished && p.exitValue() == 0) {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void close() {
        connected.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        if (ptyProcess != null) {
            ptyProcess.destroy();
            ptyProcess = null;
        }
    }

    @Override
    public String getName() {
        return connection.getDisplayName() + " [MOSH native]";
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (!connected.get() || reader == null) {
            return -1;
        }
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (connected.get() && outputStream != null) {
            outputStream.write(bytes);
            outputStream.flush();
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null) return;
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connected.get() && ptyProcess != null && ptyProcess.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        PtyProcess p = ptyProcess;
        if (p == null) return 0;
        return p.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return connected.get() && inputStream != null && inputStream.available() > 0;
    }

    @Override
    public void resize(TermSize termSize) {
        if (isConnected() && ptyProcess != null) {
            ptyProcess.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
        }
    }
}
