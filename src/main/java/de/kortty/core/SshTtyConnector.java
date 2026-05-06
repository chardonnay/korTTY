package de.kortty.core;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import de.kortty.ui.I18n;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.CommonModuleProperties;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * TtyConnector implementation for SSH connections using Apache MINA SSHD.
 * This connector integrates with SithTermFX for terminal emulation.
 */
public class SshTtyConnector implements TtyConnector {
    
    private static final Logger logger = LoggerFactory.getLogger(SshTtyConnector.class);
    public static final String SHELL_STARTUP_CLEANUP_MARKER = "\u001B]777;korTTY-startup-cleanup\u0007";
    public static final String SHELL_STARTUP_CLEANUP_MARKER_SHELL_LITERAL =
        "\\033]777;korTTY-startup-cleanup\\007";
    private static final int MAX_SHELL_STARTUP_BUFFER_LENGTH = 65536;
    private static final Pattern TERMINAL_CONTROL_SEQUENCE_PATTERN = Pattern.compile(
        "\u001B\\[[;?0-9]*[ -/]*[@-~]|\u001B\\].*?(\u0007|\u001B\\\\)");
    
    private final ServerConnection connection;
    private final String password;
    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;
    
    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private InputStreamReader reader;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Charset charset = StandardCharsets.UTF_8;
    private final Object outputWriteLock = new Object();
    private final StringBuilder pendingReadBuffer = new StringBuilder();
    private final StringBuilder shellStartupOutputBuffer = new StringBuilder();
    
    private DisconnectListener disconnectListener;
    private Thread connectionMonitorThread;
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private volatile InputInterceptor inputInterceptor;
    private volatile String shellStartupCommand;
    private volatile boolean shellStartupCleanupPending;
    private volatile String currentRemoteDirectory = "~";
    private volatile String homeRemoteDirectory = "~";
    private volatile String previousRemoteDirectory = "~";
    private final Deque<String> directoryStack = new ArrayDeque<>();
    private final StringBuilder inputLineBuffer = new StringBuilder();
    private final StringBuilder osc7Buffer = new StringBuilder();
    private final StringBuilder agentOscBuffer = new StringBuilder();
    private final Object directoryLock = new Object();
    private boolean tabCompletionPending;
    
    public SshTtyConnector(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
    }
    
    /**
     * Sets SSHKeyManager and master password for key-based authentication.
     */
    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }
    
    /**
     * Initializes the SSH connection.
     * This should be called before start() on the terminal widget.
     */
    public boolean connect() throws AuthenticationException {
        try {
            logger.info("Connecting to {}@{}:{}", connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Create and start SSH client
            client = SshClient.setUpDefaultClient();
            configureKeepAlive(client, connection.getSettings());
            
            // Configure supported auth methods explicitly.
            // For password logins we must include UserAuthPasswordFactory, otherwise
            // servers that do not offer keyboard-interactive password prompts will fail.
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                // CyberArk requires keyboard-interactive after publickey for access reason prompts.
                client.setUserAuthFactories(java.util.Arrays.asList(
                    new UserAuthPublicKeyFactory(),
                    new UserAuthKeyboardInteractiveFactory(),
                    new UserAuthPasswordFactory()
                ));
            } else {
                client.setUserAuthFactories(java.util.Arrays.asList(
                    new UserAuthPasswordFactory(),
                    new UserAuthKeyboardInteractiveFactory(),
                    new UserAuthPublicKeyFactory()
                ));
            }
            
            // Set up keyboard-interactive handler for CyberArk prompts
            // CyberArk asks for "reason for this operation" after SSH key auth succeeds
            client.setUserInteraction(new org.apache.sshd.client.auth.keyboard.UserInteraction() {
                @Override
                public boolean isInteractionAllowed(ClientSession session) {
                    return true;
                }
                
                @Override
                public String[] interactive(ClientSession session, String name, String instruction, 
                                           String lang, String[] prompt, boolean[] echo) {
                    logger.info("Keyboard-interactive request: name='{}', instruction='{}'", name, instruction);
                    
                    if (prompt == null || prompt.length == 0) {
                        return new String[0];
                    }
                    
                    String[] responses = new String[prompt.length];
                    
                    // Use JavaFX dialog to get user input for each prompt
                    final String[] finalResponses = responses;
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    
                    javafx.application.Platform.runLater(() -> {
                        try {
                            for (int i = 0; i < prompt.length; i++) {
                                logger.debug("  Prompt[{}]: '{}' (echo={})", i, prompt[i], echo[i]);
                                
                                // Check if this is an access reason prompt (contains "reason")
                                boolean isAccessReasonPrompt = prompt[i].toLowerCase().contains("reason");
                                
                                if (isAccessReasonPrompt) {
                                    // Use custom dialog with ComboBox for access reason history
                                    finalResponses[i] = showAccessReasonDialog(instruction, prompt[i]);
                                } else {
                                    // If a temporary SSH key is used, never fall back to passwords
                                    if (isTemporaryKeyAuthActive() && isPasswordPrompt(prompt[i])) {
                                        logger.warn("Temporary SSH key auth: rejecting password prompt '{}'", prompt[i]);
                                        finalResponses[i] = "";
                                        continue;
                                    }
                                    // Password/passphrase prompt: use masked input and "Passphrase for SSH key" title
                                    if (!echo[i] && isPasswordPrompt(prompt[i])) {
                                        javafx.scene.control.Dialog<String> passDialog = new javafx.scene.control.Dialog<>();
                                        passDialog.setTitle(I18n.get("dialog.sshKeyPassphraseRequired"));
                                        passDialog.setHeaderText(prompt[i]);
                                        passDialog.getDialogPane().getButtonTypes().addAll(
                                            javafx.scene.control.ButtonType.OK,
                                            javafx.scene.control.ButtonType.CANCEL);
                                        javafx.scene.control.PasswordField pf = new javafx.scene.control.PasswordField();
                                        pf.setPromptText(I18n.get("dialog.sshKeyPassphrasePrompt"));
                                        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
                                        content.getChildren().addAll(
                                            new javafx.scene.control.Label(I18n.get("dialog.sshKeyPassphrasePrompt")),
                                            pf);
                                        content.setPadding(new javafx.geometry.Insets(20));
                                        passDialog.getDialogPane().setContent(content);
                                        passDialog.setResultConverter(bt ->
                                            bt == javafx.scene.control.ButtonType.OK ? pf.getText() : null);
                                        java.util.Optional<String> result = passDialog.showAndWait();
                                        finalResponses[i] = (result != null && result.isPresent() && result.get() != null)
                                            ? result.get() : "";
                                    } else {
                                        // Plain text prompt (e.g. reason, one-time code)
                                        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
                                        dialog.setTitle("SSH Authentication");
                                        dialog.setHeaderText(instruction != null && !instruction.isEmpty() ? instruction : "Authentication Required");
                                        dialog.setContentText(prompt[i]);
                                        java.util.Optional<String> result = dialog.showAndWait();
                                        finalResponses[i] = result.orElse("");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Error showing keyboard-interactive dialog: {}", e.getMessage());
                            for (int i = 0; i < prompt.length; i++) {
                                finalResponses[i] = "";
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                    
                    try {
                        // Wait for UI thread to complete (max 5 minutes for user input)
                        latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        logger.warn("Keyboard-interactive dialog interrupted");
                        Thread.currentThread().interrupt();
                    }
                    
                    return finalResponses;
                }
                
                @Override
                public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
                    return null;
                }
            });
            
            // Note: EdDSA signature support is automatically enabled when the eddsa dependency
            // is on the classpath. The client will detect and use EdDSA signatures automatically.
            
            client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                logger.warn("Accepting server key from {}: {}", remoteAddress, serverKey.getAlgorithm());
                return true; // Accept all keys for now
            });
            client.start();
            
            // Get timeout from connection settings
            int timeoutSeconds = connection.getConnectionTimeoutSeconds();
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 15; // Default fallback
            }
            
            // Connect to server
            String username = connection.getUsername();
            logger.debug("Connecting with username: '{}'", username);
            logger.debug("Username length: {}, contains @: {}", username.length(), username.contains("@"));
            
            // Clear default key identity provider on client to avoid loading ~/.ssh keys
            client.setKeyIdentityProvider(null);
            
            session = client.connect(username, connection.getHost(), connection.getPort())
                    .verify(Duration.ofSeconds(timeoutSeconds))
                    .getSession();
            
            // Verify the session username is exactly what we set
            logger.debug("Session username after connect: '{}'", session.getUsername());
            
            // Clear any default key identity providers to avoid interference
            session.setKeyIdentityProvider(null);
            
            // Authenticate
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                authenticateWithKey();
                // Log available authentication methods after adding key
                logger.debug("Authentication methods available after adding key identity");
            } else {
                session.addPasswordIdentity(password);
            }
            
            // Perform authentication
            logger.debug("Starting authentication process...");
            session.auth().verify(Duration.ofSeconds(timeoutSeconds));
            logger.info("Authentication successful for user: {}", username);
            
            // Create shell channel
            channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(connection.getSettings().getTerminalColumns());
            channel.setPtyLines(connection.getSettings().getTerminalRows());
            
            // Configure PTY modes
            Map<PtyMode, Integer> ptyModes = new EnumMap<>(PtyMode.class);
            ptyModes.put(PtyMode.ECHO, initialPtyEchoMode(shellStartupCommand));
            ptyModes.put(PtyMode.ICRNL, 1);
            ptyModes.put(PtyMode.ONLCR, 1);
            ptyModes.put(PtyMode.ISIG, 1);
            ptyModes.put(PtyMode.ICANON, 0);  // Raw mode for proper terminal emulation
            channel.setPtyModes(ptyModes);
            
            // Open channel
            channel.open().verify(Duration.ofSeconds(10));
            
            // Get streams
            inputStream = channel.getInvertedOut();
            outputStream = channel.getInvertedIn();
            reader = new InputStreamReader(inputStream, charset);

            writeShellStartupCommandIfConfigured();
            
            connected.set(true);
            logger.info("Connected to {}", connection.getDisplayName());
            
            // Start monitoring thread to detect disconnection
            startConnectionMonitor();
            
            return true;
            
        } catch (org.apache.sshd.common.SshException e) {
            // Check if this is an authentication error
            String message = e.getMessage();
            if (message != null && (message.contains("authentication") || 
                                    message.contains("No more authentication methods"))) {
                logger.error("Authentication failed for {}: {}", connection.getDisplayName(), message);
                close();
                // Throw a specific exception for auth failures - these should NOT be retried
                throw new AuthenticationException("Authentication failed: " + message, e);
            }
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        } catch (Exception e) {
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        }
    }
    
    /**
     * Exception thrown when SSH authentication fails.
     * This indicates a configuration issue (wrong key, wrong user, etc.)
     * and should NOT trigger connection retries.
     */
    public static class AuthenticationException extends Exception {
        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Starts a background thread to monitor the connection status.
     * This thread detects when the SSH session ends and notifies the listener.
     */
    private void startConnectionMonitor() {
        connectionMonitorThread = new Thread(() -> {
            try {
                if (channel != null) {
                    logger.debug("Connection monitor started for {}", connection.getDisplayName());
                    
                    // Wait for channel to close
                    channel.waitFor(
                        java.util.EnumSet.of(
                            org.apache.sshd.client.channel.ClientChannelEvent.CLOSED,
                            org.apache.sshd.client.channel.ClientChannelEvent.EXIT_SIGNAL,
                            org.apache.sshd.client.channel.ClientChannelEvent.EXIT_STATUS
                        ),
                        0L // Wait indefinitely
                    );
                    
                    // Connection closed - check if it was normal or error
                    Integer exitStatus = channel.getExitStatus();
                    String exitSignal = channel.getExitSignal();
                    
                    final boolean wasError;
                    final String reason;
                    
                    if (exitSignal != null && !exitSignal.isEmpty()) {
                        wasError = true;
                        reason = "Connection terminated with signal: " + exitSignal;
                    } else if (exitStatus != null && exitStatus != 0) {
                        wasError = true;
                        reason = "Connection closed with exit code: " + exitStatus;
                    } else {
                        wasError = false;
                        reason = "Normal exit";
                    }
                    
                    logger.info("SSH connection ended: {} (wasError={})", reason, wasError);
                    
                    // Notify listener
                    if (disconnectListener != null) {
                        javafx.application.Platform.runLater(() -> {
                            disconnectListener.onDisconnect(reason, wasError);
                        });
                    }
                }
            } catch (Exception e) {
                logger.error("Connection monitor error: {}", e.getMessage());
                if (disconnectListener != null) {
                    javafx.application.Platform.runLater(() -> {
                        disconnectListener.onDisconnect("Connection error: " + e.getMessage(), true);
                    });
                }
            }
        }, "SSH-Monitor-" + connection.getDisplayName());
        connectionMonitorThread.setDaemon(true);
        connectionMonitorThread.start();
    }
    
    @Override
    public void close() {
        connected.set(false);
        
        // Stop monitor thread
        if (connectionMonitorThread != null) {
            connectionMonitorThread.interrupt();
        }
        
        try {
            if (channel != null) {
                channel.close();
            }
            if (session != null) {
                session.close();
            }
            if (client != null) {
                client.stop();
            }
        } catch (Exception e) {
            logger.warn("Error closing SSH connection: {}", e.getMessage());
        } finally {
            session = null;
        }
        logger.info("Disconnected from {}", connection.getDisplayName());
    }
    
    /**
     * Returns the underlying SSH session for use by SFTP (e.g. drag-and-drop file copy).
     * @return the client session, or null if not connected or session is closed
     */
    public ClientSession getSession() {
        return (session != null && session.isOpen()) ? session : null;
    }

    @Override
    public String getName() {
        return connection.getDisplayName();
    }
    
    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (!connected.get() || reader == null) {
            return -1;
        }
        while (true) {
            int pendingCount = drainPendingReadBuffer(buf, offset, length);
            if (pendingCount > 0) {
                notifyDataRead(new String(buf, offset, pendingCount));
                return pendingCount;
            }

            int count = reader.read(buf, offset, length);
            if (count <= 0) {
                return count;
            }

            String data = filterShellStartupOutput(new String(buf, offset, count));
            if (data.isEmpty()) {
                continue;
            }

            int copied = copyReadData(data, buf, offset, length);
            if (copied > 0) {
                notifyDataRead(new String(buf, offset, copied));
                return copied;
            }
        }
    }

    private int drainPendingReadBuffer(char[] buf, int offset, int length) {
        synchronized (pendingReadBuffer) {
            if (pendingReadBuffer.isEmpty()) {
                return 0;
            }
            int count = Math.min(length, pendingReadBuffer.length());
            pendingReadBuffer.getChars(0, count, buf, offset);
            pendingReadBuffer.delete(0, count);
            return count;
        }
    }

    private int copyReadData(String data, char[] buf, int offset, int length) {
        int copied = Math.min(length, data.length());
        data.getChars(0, copied, buf, offset);
        if (copied < data.length()) {
            synchronized (pendingReadBuffer) {
                pendingReadBuffer.append(data, copied, data.length());
            }
        }
        return copied;
    }

    private void notifyDataRead(String data) {
        trackPotentialTabCompletionOutput(data);
        updateCurrentDirectoryFromOutput(data);
        for (DataListener dataListener : dataListeners) {
            try {
                dataListener.onData(data);
            } catch (Exception e) {
                // Don't let listener errors break the connection
                logger.warn("Data listener error: {}", e.getMessage());
            }
        }
    }

    private String filterShellStartupOutput(String data) {
        if (!shellStartupCleanupPending || data == null || data.isEmpty()) {
            return data != null ? data : "";
        }

        synchronized (shellStartupOutputBuffer) {
            shellStartupOutputBuffer.append(data);
            int markerStart = shellStartupOutputBuffer.indexOf(SHELL_STARTUP_CLEANUP_MARKER);
            if (markerStart < 0) {
                if (shellStartupOutputBuffer.length() <= MAX_SHELL_STARTUP_BUFFER_LENGTH) {
                    return "";
                }
                String buffered = shellStartupOutputBuffer.toString();
                shellStartupOutputBuffer.setLength(0);
                shellStartupCleanupPending = false;
                logger.warn("SSH shell startup cleanup marker was not received before buffer limit");
                return buffered;
            }

            String beforeMarker = shellStartupOutputBuffer.substring(0, markerStart);
            String afterMarker = shellStartupOutputBuffer.substring(markerStart + SHELL_STARTUP_CLEANUP_MARKER.length());
            shellStartupOutputBuffer.setLength(0);
            shellStartupCleanupPending = false;
            return removeShellStartupPromptBeforeCleanup(beforeMarker) + afterMarker;
        }
    }

    static String removeShellStartupPromptBeforeCleanup(String outputBeforeCleanup) {
        if (outputBeforeCleanup == null || outputBeforeCleanup.isEmpty()) {
            return "";
        }

        int promptEnd = outputBeforeCleanup.length();
        while (promptEnd > 0) {
            char ch = outputBeforeCleanup.charAt(promptEnd - 1);
            if (ch != '\r' && ch != '\n') {
                break;
            }
            promptEnd--;
        }

        int promptStart = lastLineStart(outputBeforeCleanup, promptEnd);
        String promptLine = outputBeforeCleanup.substring(promptStart, promptEnd);
        if (!looksLikeShellPrompt(promptLine)) {
            return outputBeforeCleanup;
        }

        int visibleStart = leadingTerminalControlSequenceLength(promptLine);
        return outputBeforeCleanup.substring(0, promptStart)
            + promptLine.substring(0, visibleStart);
    }

    private static int lastLineStart(String text, int endExclusive) {
        int lastCarriageReturn = text.lastIndexOf('\r', Math.max(0, endExclusive - 1));
        int lastLineFeed = text.lastIndexOf('\n', Math.max(0, endExclusive - 1));
        return Math.max(lastCarriageReturn, lastLineFeed) + 1;
    }

    private static boolean looksLikeShellPrompt(String line) {
        String visible = TERMINAL_CONTROL_SEQUENCE_PATTERN.matcher(line).replaceAll("").stripTrailing();
        return visible.endsWith("$")
            || visible.endsWith("#")
            || visible.endsWith("%")
            || visible.endsWith(">")
            || visible.matches(".*\\[[^\\]]+\\]\\$");
    }

    private static int leadingTerminalControlSequenceLength(String line) {
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '\u001B') {
                break;
            }
            int end = terminalControlSequenceEnd(line, index);
            if (end <= index) {
                break;
            }
            index = end;
        }
        return index;
    }

    private static int terminalControlSequenceEnd(String text, int start) {
        if (start + 1 >= text.length()) {
            return -1;
        }
        char type = text.charAt(start + 1);
        if (type == '[') {
            for (int i = start + 2; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch >= '@' && ch <= '~') {
                    return i + 1;
                }
            }
            return -1;
        }
        if (type == ']') {
            int bellEnd = text.indexOf('\u0007', start + 2);
            int stEnd = text.indexOf("\u001B\\", start + 2);
            if (bellEnd < 0) {
                return stEnd >= 0 ? stEnd + 2 : -1;
            }
            if (stEnd < 0 || bellEnd < stEnd) {
                return bellEnd + 1;
            }
            return stEnd + 2;
        }
        return start + 2;
    }
    
    @Override
    public void write(byte[] bytes) throws IOException {
        if (connected.get() && outputStream != null) {
            synchronized (outputWriteLock) {
                byte[] bytesToWrite = applyInputInterceptor(bytes);
                if (bytesToWrite == null || bytesToWrite.length == 0) {
                    return;
                }
                trackPotentialDirectoryChange(bytesToWrite);
                outputStream.write(bytesToWrite);
                outputStream.flush();
            }
        }
    }
    
    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(charset));
    }
    
    @Override
    public boolean isConnected() {
        return connected.get() && channel != null && channel.isOpen();
    }
    
    @Override
    public int waitFor() throws InterruptedException {
        if (channel != null) {
            // Wait for channel to close
            while (channel.isOpen()) {
                Thread.sleep(100);
            }
        }
        return 0;
    }
    
    @Override
    public boolean ready() throws IOException {
        return connected.get() && inputStream != null && inputStream.available() > 0;
    }
    
    @Override
    public void resize(TermSize termSize) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.sendWindowChange(termSize.getColumns(), termSize.getRows());
            } catch (Exception e) {
                logger.warn("Failed to resize terminal: {}", e.getMessage());
            }
        }
    }
    
    public void setDisconnectListener(DisconnectListener listener) {
        this.disconnectListener = listener;
    }
    
    public void setDataListener(DataListener listener) {
        dataListeners.clear();
        if (listener != null) {
            dataListeners.add(listener);
        }
    }

    public void addDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.addIfAbsent(listener);
        }
    }

    public void removeDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.remove(listener);
        }
    }

    public void setInputInterceptor(InputInterceptor inputInterceptor) {
        this.inputInterceptor = inputInterceptor;
    }

    public void setShellStartupCommand(String shellStartupCommand) {
        this.shellStartupCommand = shellStartupCommand;
    }

    public boolean hasShellStartupCommandConfigured() {
        return hasShellStartupCommand();
    }

    public void sendShellKeepAliveBlankLine() throws IOException {
        if (isConnected() && outputStream != null) {
            synchronized (outputWriteLock) {
                outputStream.write('\r');
                outputStream.flush();
            }
        }
    }
    
    public ServerConnection getConnection() {
        return connection;
    }

    /**
     * Returns the best-known remote working directory for this terminal session.
     * The value is initialized from SFTP and then updated passively from terminal
     * output (OSC 7) and typed directory-changing commands.
     */
    public String getCurrentRemoteDirectory() {
        synchronized (directoryLock) {
            return currentRemoteDirectory;
        }
    }

    public String getHomeRemoteDirectory() {
        synchronized (directoryLock) {
            return homeRemoteDirectory;
        }
    }

    public void updateCurrentRemoteDirectoryHint(String directory) {
        if (directory != null && directory.startsWith("/")) {
            setCurrentRemoteDirectory(directory);
        }
    }

    private void updateCurrentDirectoryFromOutput(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        updateCurrentDirectoryFromAgentOsc(data);
        synchronized (osc7Buffer) {
            osc7Buffer.append(data);
            while (true) {
                int start = osc7Buffer.indexOf("\u001B]7;file://");
                if (start < 0) {
                    trimOsc7Buffer();
                    return;
                }
                int bellEnd = osc7Buffer.indexOf("\u0007", start);
                int stEnd = osc7Buffer.indexOf("\u001B\\", start);
                int end = -1;
                int terminatorLength = 0;
                if (bellEnd >= 0 && (stEnd < 0 || bellEnd < stEnd)) {
                    end = bellEnd;
                    terminatorLength = 1;
                } else if (stEnd >= 0) {
                    end = stEnd;
                    terminatorLength = 2;
                }
                if (end < 0) {
                    if (start > 0) {
                        osc7Buffer.delete(0, start);
                    }
                    trimOsc7Buffer();
                    return;
                }
                String uriText = osc7Buffer.substring(start + 4, end);
                updateCurrentDirectoryFromOsc7(uriText);
                osc7Buffer.delete(0, end + terminatorLength);
            }
        }
    }

    private void updateCurrentDirectoryFromAgentOsc(String data) {
        synchronized (agentOscBuffer) {
            agentOscBuffer.append(data);
            while (true) {
                String prefix = "\u001B]777;korTTY-agent;";
                int start = agentOscBuffer.indexOf(prefix);
                if (start < 0) {
                    trimAgentOscBuffer();
                    return;
                }
                int bellEnd = agentOscBuffer.indexOf("\u0007", start);
                int stEnd = agentOscBuffer.indexOf("\u001B\\", start);
                int end = -1;
                int terminatorLength = 0;
                if (bellEnd >= 0 && (stEnd < 0 || bellEnd < stEnd)) {
                    end = bellEnd;
                    terminatorLength = 1;
                } else if (stEnd >= 0) {
                    end = stEnd;
                    terminatorLength = 2;
                }
                if (end < 0) {
                    if (start > 0) {
                        agentOscBuffer.delete(0, start);
                    }
                    trimAgentOscBuffer();
                    return;
                }
                String payload = agentOscBuffer.substring(start + prefix.length(), end);
                agentOscBuffer.delete(0, end + terminatorLength);
                String cwd = extractWorkingDirectoryFromAgentOscPayload(payload);
                if (cwd != null && !cwd.isBlank()) {
                    setCurrentRemoteDirectory(cwd);
                    logger.debug("Updated remote directory from terminal agent hook: {}", cwd);
                }
            }
        }
    }

    private void updateCurrentDirectoryFromOsc7(String uriText) {
        String path = extractWorkingDirectoryFromOsc7Uri(uriText);
        if (path != null && !path.isBlank()) {
            setCurrentRemoteDirectory(path);
            logger.debug("Updated remote directory from OSC 7: {}", path);
        } else {
            logger.debug("Failed to parse OSC 7 URI '{}'", uriText);
        }
    }

    private void trimOsc7Buffer() {
        int maxLength = 4096;
        if (osc7Buffer.length() > maxLength) {
            osc7Buffer.delete(0, osc7Buffer.length() - maxLength);
        }
    }

    private void trimAgentOscBuffer() {
        int maxLength = 4096;
        if (agentOscBuffer.length() > maxLength) {
            agentOscBuffer.delete(0, agentOscBuffer.length() - maxLength);
        }
    }

    private void trackPotentialDirectoryChange(byte[] bytes) {
        String text = new String(bytes, charset);
        synchronized (inputLineBuffer) {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\r' || ch == '\n') {
                    processInputLine(inputLineBuffer.toString());
                    inputLineBuffer.setLength(0);
                    tabCompletionPending = false;
                } else if (ch == '\t') {
                    tabCompletionPending = true;
                } else if (ch == '\b' || ch == 127) {
                    if (inputLineBuffer.length() > 0) {
                        inputLineBuffer.deleteCharAt(inputLineBuffer.length() - 1);
                    }
                    tabCompletionPending = false;
                } else if (!Character.isISOControl(ch)) {
                    inputLineBuffer.append(ch);
                    tabCompletionPending = false;
                }
            }
            if (inputLineBuffer.length() > 2048) {
                inputLineBuffer.delete(0, inputLineBuffer.length() - 2048);
            }
        }
    }

    private void trackPotentialTabCompletionOutput(String data) {
        synchronized (inputLineBuffer) {
            if (!tabCompletionPending) {
                return;
            }
            String completedLine = applyTabCompletionOutputToInputLine(inputLineBuffer.toString(), data);
            if (completedLine != null && completedLine.length() <= 2048) {
                inputLineBuffer.setLength(0);
                inputLineBuffer.append(completedLine);
            }
            tabCompletionPending = completedLine != null
                && data.indexOf('\r') < 0
                && data.indexOf('\n') < 0;
        }
    }

    static String applyTabCompletionOutputToInputLine(String inputLine, String terminalOutput) {
        if (inputLine == null || inputLine.isEmpty() || terminalOutput == null || terminalOutput.isEmpty()) {
            return null;
        }
        String text = TERMINAL_CONTROL_SEQUENCE_PATTERN.matcher(terminalOutput).replaceAll("");
        if (text.isEmpty()) {
            return null;
        }
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            String lastLine = lastTerminalLine(text);
            String candidate = stripIsoControls(lastLine);
            int commandStart = candidate.lastIndexOf(inputLine);
            return commandStart >= 0 ? candidate.substring(commandStart) : null;
        }
        String suffix = stripIsoControls(text);
        return suffix.isEmpty() ? null : inputLine + suffix;
    }

    private static String lastTerminalLine(String text) {
        int lineStart = Math.max(text.lastIndexOf('\r'), text.lastIndexOf('\n'));
        return lineStart >= 0 ? text.substring(lineStart + 1) : text;
    }

    private static String stripIsoControls(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isISOControl(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private byte[] applyInputInterceptor(byte[] bytes) throws IOException {
        InputInterceptor interceptor = inputInterceptor;
        if (interceptor == null || bytes == null || bytes.length == 0) {
            return bytes;
        }
        return interceptor.intercept(bytes);
    }

    private boolean hasShellStartupCommand() {
        String command = shellStartupCommand;
        return command != null && !command.isBlank();
    }

    static int initialPtyEchoMode(String shellStartupCommand) {
        return shellStartupCommand != null && !shellStartupCommand.isBlank() ? 0 : 1;
    }

    static void configureKeepAlive(SshClient sshClient, ConnectionSettings settings) {
        if (sshClient == null) {
            return;
        }
        boolean enabled = settings == null || settings.isSshKeepAliveEnabled();
        if (!enabled) {
            CommonModuleProperties.SESSION_HEARTBEAT_TYPE.set(
                sshClient,
                SessionHeartbeatController.HeartbeatType.NONE);
            CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.set(sshClient, Duration.ZERO);
            CoreModuleProperties.SOCKET_KEEPALIVE.set(sshClient, false);
            logger.debug("SSH keep-alive disabled by connection settings");
            return;
        }

        int configuredInterval = settings != null ? settings.getSshKeepAliveInterval() : 60;
        int intervalSeconds = Math.max(5, Math.min(configuredInterval, 600));
        CommonModuleProperties.SESSION_HEARTBEAT_TYPE.set(
            sshClient,
            SessionHeartbeatController.HeartbeatType.IGNORE);
        CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.set(sshClient, Duration.ofSeconds(intervalSeconds));
        CoreModuleProperties.SOCKET_KEEPALIVE.set(sshClient, true);
        logger.debug("SSH keep-alive enabled: SSH_MSG_IGNORE every {} seconds", intervalSeconds);
    }

    private void writeShellStartupCommandIfConfigured() throws IOException {
        String command = shellStartupCommand;
        if (command == null || command.isBlank() || outputStream == null) {
            return;
        }
        String commandWithNewline = command.endsWith("\n") ? command : command + "\n";
        synchronized (outputWriteLock) {
            shellStartupCleanupPending = command.contains(SHELL_STARTUP_CLEANUP_MARKER)
                || command.contains(SHELL_STARTUP_CLEANUP_MARKER_SHELL_LITERAL);
            outputStream.write(commandWithNewline.getBytes(charset));
            outputStream.flush();
        }
        logger.debug("Wrote SSH shell startup command");
    }

    private void processInputLine(String inputLine) {
        String segment = firstCommandSegment(inputLine);
        if (segment.isEmpty()) {
            return;
        }
        if (segment.equals("cd") || segment.startsWith("cd ")) {
            applyCdCommand(segment);
        } else if (segment.equals("pushd") || segment.startsWith("pushd ")) {
            applyPushdCommand(segment);
        } else if (segment.equals("popd")) {
            applyPopdCommand();
        }
    }

    private String firstCommandSegment(String inputLine) {
        if (inputLine == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < inputLine.length(); i++) {
            char ch = inputLine.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                out.append(ch);
                continue;
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append(ch);
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(ch);
                continue;
            }
            if (!inSingle && !inDouble) {
                if (ch == ';' || ch == '|') {
                    break;
                }
                if (ch == '&' && i + 1 < inputLine.length() && inputLine.charAt(i + 1) == '&') {
                    break;
                }
            }
            out.append(ch);
        }
        return out.toString().trim();
    }

    private void applyCdCommand(String segment) {
        String arg = segment.length() <= 2 ? "" : segment.substring(2).trim();
        if (arg.isEmpty()) {
            setCurrentRemoteDirectory(homeRemoteDirectory);
            return;
        }
        String target = unquote(arg);
        if ("-".equals(target)) {
            setCurrentRemoteDirectory(previousRemoteDirectory);
            return;
        }
        if (target.startsWith("~")) {
            setCurrentRemoteDirectory(normalizeRemotePath(homeRemoteDirectory + target.substring(1)));
            return;
        }
        if (target.startsWith("/")) {
            setCurrentRemoteDirectory(normalizeRemotePath(target));
            return;
        }
        setCurrentRemoteDirectory(normalizeRemotePath(currentRemoteDirectory + "/" + target));
    }

    private void applyPushdCommand(String segment) {
        String arg = segment.length() <= 5 ? "" : segment.substring(5).trim();
        if (arg.isEmpty()) {
            String stacked = directoryStack.pollFirst();
            if (stacked != null) {
                directoryStack.addFirst(currentRemoteDirectory);
                setCurrentRemoteDirectory(stacked);
            }
            return;
        }
        directoryStack.addFirst(currentRemoteDirectory);
        String target = unquote(arg);
        if (target.startsWith("/")) {
            setCurrentRemoteDirectory(normalizeRemotePath(target));
        } else if (target.startsWith("~")) {
            setCurrentRemoteDirectory(normalizeRemotePath(homeRemoteDirectory + target.substring(1)));
        } else {
            setCurrentRemoteDirectory(normalizeRemotePath(currentRemoteDirectory + "/" + target));
        }
    }

    private void applyPopdCommand() {
        String stacked = directoryStack.pollFirst();
        if (stacked != null) {
            setCurrentRemoteDirectory(stacked);
        }
    }

    private void setCurrentRemoteDirectory(String newDirectory) {
        if (newDirectory == null || newDirectory.isBlank()) {
            return;
        }
        String normalized = normalizeRemotePath(newDirectory);
        if (normalized.isBlank()) {
            return;
        }
        synchronized (directoryLock) {
            if (!normalized.equals(currentRemoteDirectory)) {
                previousRemoteDirectory = currentRemoteDirectory;
                currentRemoteDirectory = normalized;
                logger.debug("Tracked remote directory updated to {}", normalized);
            }
        }
    }

    private String normalizeRemotePath(String path) {
        if (path == null || path.isBlank()) {
            return currentRemoteDirectory;
        }
        boolean absolute = path.startsWith("/");
        String[] parts = path.split("/");
        Deque<String> normalized = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!normalized.isEmpty()) {
                    normalized.removeLast();
                }
            } else {
                normalized.addLast(part);
            }
        }
        StringBuilder result = new StringBuilder(absolute ? "/" : "");
        boolean first = true;
        for (String part : normalized) {
            if (!first) {
                result.append('/');
            }
            result.append(part);
            first = false;
        }
        if (result.isEmpty()) {
            return absolute ? "/" : ".";
        }
        return result.toString();
    }

    public static String extractWorkingDirectoryFromAgentOscPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        int firstSeparator = payload.indexOf(';');
        int secondSeparator = firstSeparator >= 0 ? payload.indexOf(';', firstSeparator + 1) : -1;
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1) {
            return null;
        }
        String encodedCwd = payload.substring(firstSeparator + 1, secondSeparator);
        try {
            String cwd = new String(Base64.getDecoder().decode(encodedCwd), StandardCharsets.UTF_8).trim();
            return cwd.startsWith("/") ? cwd : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String extractWorkingDirectoryFromOsc7Uri(String uriText) {
        if (uriText == null || uriText.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(uriText);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String path = uri.getPath();
            return path != null && path.startsWith("/") ? path : null;
        } catch (IllegalArgumentException e) {
            String filePrefix = "file://";
            if (!uriText.startsWith(filePrefix)) {
                return null;
            }
            int pathStart = uriText.indexOf('/', filePrefix.length());
            if (pathStart < 0) {
                return null;
            }
            String path = uriText.substring(pathStart);
            return path.startsWith("/") ? path : null;
        }
    }

    private String unquote(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
    
    /**
     * Authenticates using a private key file.
     */
    private void authenticateWithKey() throws Exception {
        String[] keyPathRef = new String[1];
        String[] passphraseRef = new String[1];
        
        // Try to get key from SSHKeyManager if sshKeyId is set
        if (connection.getSshKeyId() != null && sshKeyManager != null && masterPassword != null) {
            try {
                sshKeyManager.findKeyById(connection.getSshKeyId()).ifPresent(key -> {
                    try {
                        keyPathRef[0] = sshKeyManager.getEffectiveKeyPath(key);
                        passphraseRef[0] = sshKeyManager.getPassphrase(key, masterPassword);
                    } catch (Exception e) {
                        logger.error("Failed to get key from SSHKeyManager", e);
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to find key by ID", e);
            }
        }
        
        // Fallback to connection's key path if not found in manager
        String keyPath = keyPathRef[0];
        if (keyPath == null || keyPath.trim().isEmpty()) {
            keyPath = connection.getPrivateKeyPath();
        }
        
        if (keyPath == null || keyPath.trim().isEmpty()) {
            throw new Exception("Kein SSH-Key-Pfad angegeben");
        }
        
        // Check if this is a temporary SSH key (starts with "TEMPORARY:")
        if (keyPath.startsWith("TEMPORARY:")) {
            String keyContent = keyPath.substring("TEMPORARY:".length());
            java.io.File tempFile = null;
            try {
                // Ensure key content ends with a newline - OpenSSH keys MUST end with a newline character
                String keyContentFixed = keyContent;
                if (!keyContent.endsWith("\n")) {
                    keyContentFixed = keyContent + "\n";
                    logger.debug("Added missing trailing newline to key content");
                }
                
                // DEBUG: Log key content details for troubleshooting
                logger.debug("Temporary SSH key content length: {} chars (after fix: {} chars)", 
                    keyContent.length(), keyContentFixed.length());
                if (keyContentFixed.length() > 100) {
                    logger.debug("Key content starts with: {}", keyContentFixed.substring(0, 50).replace("\n", "\\n"));
                    logger.debug("Key content ends with: {}", keyContentFixed.substring(keyContentFixed.length() - 50).replace("\n", "\\n"));
                }
                
                // Write temporary key to a temporary file
                tempFile = java.io.File.createTempFile("kortty_temp_key_", ".key");
                tempFile.deleteOnExit();
                
                // Write key content to file
                try (java.io.FileWriter writer = new java.io.FileWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(keyContentFixed);
                }
                
                // DEBUG: Log temp file path
                logger.debug("Temporary key file: {}", tempFile.getAbsolutePath());
                
                // Set file permissions to 600 (read/write for owner only) - required by SSH
                try {
                    java.nio.file.Files.setPosixFilePermissions(
                        tempFile.toPath(),
                        java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                        )
                    );
                } catch (UnsupportedOperationException e) {
                    // Windows doesn't support POSIX permissions, try alternative
                    tempFile.setReadable(false, false);
                    tempFile.setReadable(true, true);
                    tempFile.setWritable(false, false);
                    tempFile.setWritable(true, true);
                }
                
                // Load key pair from temporary file using FileKeyPairProvider
                // Use setKeyIdentityProvider instead of addPublicKeyIdentity for better EdDSA support
                FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(tempFile.toPath());
                
                // Set the key identity provider on the session - this is the recommended approach
                // for FileKeyPairProvider and works better with EdDSA keys
                session.setKeyIdentityProvider(keyPairProvider);
                
                // Also verify that we can load at least one key pair
                Iterable<java.security.KeyPair> keyPairs = keyPairProvider.loadKeys(session);
                
                if (keyPairs == null) {
                    throw new Exception("Could not load temporary SSH key: keyPairs is null");
                }
                
                // Get iterator and check if it has elements
                java.util.Iterator<java.security.KeyPair> iterator = keyPairs.iterator();
                if (!iterator.hasNext()) {
                    throw new Exception("Could not parse temporary SSH key: no key pairs found");
                }
                
                // Use the first key pair for logging
                java.security.KeyPair keyPair = iterator.next();
                if (keyPair == null) {
                    throw new Exception("Could not parse temporary SSH key: keyPair is null");
                }
                
                // Verify key pair has both private and public key
                if (keyPair.getPrivate() == null || keyPair.getPublic() == null) {
                    throw new Exception("Invalid temporary SSH key: missing private or public key component");
                }
                
                // Log key details for debugging
                String pubKeyAlgorithm = keyPair.getPublic().getAlgorithm();
                String pubKeyFormat = keyPair.getPublic().getFormat();
                String privKeyAlgorithm = keyPair.getPrivate().getAlgorithm();
                String privKeyFormat = keyPair.getPrivate().getFormat();
                String pubKeyClass = keyPair.getPublic().getClass().getName();
                String privKeyClass = keyPair.getPrivate().getClass().getName();
                
                logger.info("Loaded temporary SSH key details:");
                logger.info("  Public key - Algorithm: {}, Format: {}, Size: {} bytes, Class: {}", 
                    pubKeyAlgorithm, pubKeyFormat, keyPair.getPublic().getEncoded().length, pubKeyClass);
                logger.info("  Private key - Algorithm: {}, Format: {}, Class: {}", 
                    privKeyAlgorithm, privKeyFormat, privKeyClass);
                
                // Clear any existing key identities and add ONLY our temporary key
                // This ensures no other keys interfere with authentication
                session.setKeyIdentityProvider(null);
                session.addPublicKeyIdentity(keyPair);
                
                logger.info("Added temporary key to session:");
                logger.info("  Public key: {}", keyPair.getPublic());
                logger.info("  Session username: '{}'", session.getUsername());
                logger.info("  Session host: '{}'", session.getConnectAddress());
                return;
            } catch (Exception e) {
                logger.error("Failed to load temporary SSH key from file: {}", 
                    tempFile != null ? tempFile.getAbsolutePath() : "unknown", e);
                throw new Exception("Error loading temporary SSH key: " + e.getMessage(), e);
            }
        }
        
        java.nio.file.Path keyFilePath = java.nio.file.Paths.get(keyPath);
        if (!java.nio.file.Files.exists(keyFilePath)) {
            throw new Exception("SSH-Key-Datei existiert nicht: " + keyPath);
        }
        
        // Use passphrase from manager if available, otherwise from connection (decrypt only; never use plain)
        String passphrase = passphraseRef[0];
        if (passphrase == null) {
            String stored = connection.getPrivateKeyPassphrase();
            if (stored != null && !stored.isBlank() && masterPassword != null) {
                try {
                    EncryptionService encryptionService = new EncryptionService();
                    passphrase = encryptionService.decryptPassword(stored, masterPassword);
                } catch (Exception e) {
                    String msg = "Stored private key passphrase could not be decrypted (legacy/plaintext or malformed value). Re-enter the passphrase in connection settings or migrate stored keys.";
                    logger.error("{}. Cause: {}", msg, e.getMessage(), e);
                    throw new AuthenticationException(msg, e);
                }
            }
        }
        
        try {
            // Load key pair from file using FileKeyPairProvider
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyFilePath);
            
            // Set passphrase if provided
            if (passphrase != null && !passphrase.isEmpty()) {
                final String finalPassphrase = passphrase;
                keyPairProvider.setPasswordFinder((sess, path, retryIndex) -> finalPassphrase);
            }
            
            // Load the key pair
            Iterable<java.security.KeyPair> keyPairs = keyPairProvider.loadKeys(session);
            
            if (keyPairs == null) {
                throw new Exception("Konnte SSH-Key nicht laden: " + keyPath);
            }
            
            // Add all key pairs to session
            int count = 0;
            for (java.security.KeyPair keyPair : keyPairs) {
                session.addPublicKeyIdentity(keyPair);
                count++;
            }
            
            if (count == 0) {
                throw new Exception("Keine KeyPairs in SSH-Key-Datei gefunden: " + keyPath);
            }
            
            logger.info("Added {} public key identity/identities from {}", count, keyPath);
        } catch (Exception e) {
            logger.error("Failed to load SSH key from " + keyPath, e);
            throw new Exception("SSH-Key-Authentifizierung fehlgeschlagen: " + e.getMessage(), e);
        }
    }
    
    /**
     * Shows a dialog for entering the access reason with history from previous entries.
     * Uses a ComboBox that allows both selection from history and text input.
     */
    private String showAccessReasonDialog(String instruction, String promptText) {
        // Create custom dialog
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("SSH Authentication - Access Reason");
        dialog.setHeaderText(instruction != null && !instruction.isEmpty() ? instruction : "Authentication Required");
        
        // Set the button types
        javafx.scene.control.ButtonType okButtonType = new javafx.scene.control.ButtonType("OK", 
            javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, javafx.scene.control.ButtonType.CANCEL);
        
        // Create the ComboBox with editable text field
        javafx.scene.control.ComboBox<String> reasonComboBox = new javafx.scene.control.ComboBox<>();
        reasonComboBox.setEditable(true);
        reasonComboBox.setPromptText("Enter or select access reason...");
        reasonComboBox.setPrefWidth(400);
        
        // Load history from GlobalSettings
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                java.util.List<String> history = gsm.getSettings().getAccessReasonHistory();
                if (history != null && !history.isEmpty()) {
                    reasonComboBox.getItems().addAll(history);
                    // Pre-select the most recent entry
                    reasonComboBox.setValue(history.get(0));
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load access reason history: {}", e.getMessage());
        }
        
        // Create layout
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.getChildren().addAll(
            new javafx.scene.control.Label(promptText),
            reasonComboBox
        );
        content.setPadding(new javafx.geometry.Insets(20, 20, 10, 20));
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(450);
        
        // Focus on the ComboBox editor
        javafx.application.Platform.runLater(() -> {
            reasonComboBox.requestFocus();
            if (reasonComboBox.getEditor() != null) {
                reasonComboBox.getEditor().selectAll();
            }
        });
        
        // Convert the result
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == okButtonType) {
                String value = reasonComboBox.getValue();
                if (value == null || value.trim().isEmpty()) {
                    value = reasonComboBox.getEditor().getText();
                }
                return value != null ? value.trim() : "";
            }
            return "";
        });
        
        java.util.Optional<String> result = dialog.showAndWait();
        String reason = result.orElse("");
        
        // Save to history if not empty
        if (!reason.isEmpty()) {
            try {
                de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
                if (gsm != null && gsm.getSettings() != null) {
                    gsm.getSettings().addAccessReason(reason);
                    gsm.save();
                }
            } catch (Exception e) {
                logger.warn("Could not save access reason to history: {}", e.getMessage());
            }
        }
        
        return reason;
    }

    private boolean isTemporaryKeyAuthActive() {
        String keyPath = connection.getPrivateKeyPath();
        return keyPath != null && keyPath.startsWith("TEMPORARY:");
    }

    private boolean isPasswordPrompt(String promptText) {
        if (promptText == null) {
            return false;
        }
        String lower = promptText.toLowerCase();
        return lower.contains("password") ||
               lower.contains("passcode") ||
               lower.contains("passphrase") ||
               lower.contains("pin") ||
               lower.contains("vault");
    }

    
    /**
     * Listener for data received from the SSH connection.
     */
    public interface DataListener {
        void onData(String data);
    }

    /**
     * Intercepts outbound terminal input before it is written to the SSH channel.
     */
    @FunctionalInterface
    public interface InputInterceptor {
        byte[] intercept(byte[] bytes) throws IOException;
    }
}
