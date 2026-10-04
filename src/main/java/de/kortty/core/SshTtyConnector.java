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
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.util.buffer.Buffer;
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
 *
 * <p>The connector never opens the connection's SSH tunnels itself: every split pane and every
 * reconnect builds its own connector, so tunnels opened here would collide on their ports. The
 * owning terminal tab attaches its {@link SshTunnelManager} to the session of its first pane
 * instead; this class only installs the {@linkplain SshTunnelManager#clientForwardingFilter()
 * client forwarding filter} remote tunnels need.
 */
public class SshTtyConnector implements ObservableTtyConnector {
    
    private static final Logger logger = LoggerFactory.getLogger(SshTtyConnector.class);
    public static final String SHELL_STARTUP_CLEANUP_MARKER = "\u001B]777;korTTY-startup-cleanup\u0007";
    public static final String SHELL_STARTUP_CLEANUP_MARKER_SHELL_LITERAL =
        "\\033]777;korTTY-startup-cleanup\\007";
    private static final int MAX_SHELL_STARTUP_BUFFER_LENGTH = 65536;
    private static final Pattern TERMINAL_CONTROL_SEQUENCE_PATTERN = Pattern.compile(
        "\u001B\\[[;?0-9]*[ -/]*[@-~]|\u001B\\].*?(\u0007|\u001B\\\\)");
    
    private final ServerConnection connection;
    private final String password;
    private final SshHostKeyTrustManager hostKeyTrustManager;
    private volatile SshHostKeyTrustManager.ReplacePolicy hostKeyReplacePolicy =
        SshHostKeyTrustManager.ReplacePolicy.NEVER;
    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;

    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    /** Established bastion hop when the connection has an enabled jump server; null otherwise. */
    private JumpHostSupport.JumpTunnel jumpTunnel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private InputStreamReader reader;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** Resolved once per connector: an encoding change applies on the next connect or reconnect. */
    private final Charset charset;
    private final Object outputWriteLock = new Object();
    private final StringBuilder pendingReadBuffer = new StringBuilder();
    private final StringBuilder shellStartupOutputBuffer = new StringBuilder();
    
    private DisconnectListener disconnectListener;
    private Thread connectionMonitorThread;
    private Thread livenessProbeThread;
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InputActivityListener> inputActivityListeners = new CopyOnWriteArrayList<>();
    private volatile InputInterceptor inputInterceptor;
    private volatile String shellStartupCommand;
    private volatile boolean shellStartupCleanupPending;
    private volatile String currentRemoteDirectory = "~";
    private volatile String homeRemoteDirectory = "~";
    private volatile String previousRemoteDirectory = "~";
    private final Deque<String> directoryStack = new ArrayDeque<>();
    private final StringBuilder inputLineBuffer = new StringBuilder();
    private final ShellSessionChangeTracker sessionChangeTracker = new ShellSessionChangeTracker();
    private final StringBuilder osc7Buffer = new StringBuilder();
    private final StringBuilder agentOscBuffer = new StringBuilder();
    private final Object directoryLock = new Object();
    /**
     * The OSC 7 host learned while the session was native, compared on its short name; {@code null}
     * until the first such report. Guarded by {@link #directoryLock}.
     */
    private String osc7BaselineHost;
    /**
     * Sticky: an OSC 7 named a host other than the baseline. Cleared only by an OSC 7 with the
     * baseline host or by a reconnect, never by the prompt heuristic, so the verdict cannot flip
     * back and forth with every prompt. Guarded by {@link #directoryLock}.
     */
    private boolean osc7Foreign;
    private final CopyOnWriteArrayList<RemoteDirectoryChange.Listener> remoteDirectoryListeners =
        new CopyOnWriteArrayList<>();
    private boolean tabCompletionPending;
    
    public SshTtyConnector(ServerConnection connection, String password) {
        this(connection, password, SshHostKeyTrustManager.shared());
    }

    SshTtyConnector(
        ServerConnection connection,
        String password,
        SshHostKeyTrustManager hostKeyTrustManager) {

        this.connection = connection;
        this.password = password;
        this.hostKeyTrustManager = java.util.Objects.requireNonNull(hostKeyTrustManager, "hostKeyTrustManager");
        this.charset = TerminalEncodingSupport.resolveFromSettings(connection);
    }

    @Override
    public Charset getCharset() {
        return charset;
    }
    
    /**
     * Sets SSHKeyManager and master password for key-based authentication.
     */
    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }

    /**
     * Hands this connector the vault it needs, whatever the target's authentication method.
     *
     * <p>The master password is always set: besides a key passphrase it also decrypts the stored
     * jump server password, which a password or keyboard-interactive target needs just as much as
     * a key-based one. The key manager is only used for a {@code PUBLIC_KEY} target, as before.
     *
     * @param keyManager the managed SSH keys; may be {@code null}
     * @param masterPassword the vault's master password, or {@code null} while the vault is locked
     */
    public void configureVault(SSHKeyManager keyManager, char[] masterPassword) {
        this.masterPassword = masterPassword;
        this.sshKeyManager = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY
            ? keyManager
            : null;
    }

    /**
     * Shares the owning tab's answered access reasons, so a split does not ask the user again for
     * something they already answered when the tab was opened. Left unset the dialog is shown for
     * every authentication, which is the behaviour outside a terminal tab.
     */
    public void setAccessReasonMemory(AccessReasonMemory accessReasonMemory) {
        this.accessReasonMemory = accessReasonMemory;
    }

    /**
     * Whether a changed host key (of the target or of its jump server) may be reviewed and replaced
     * during {@link #connect()}. Only a terminal tab the user opened sets
     * {@link SshHostKeyTrustManager.ReplacePolicy#INTERACTIVE}; the default
     * {@link SshHostKeyTrustManager.ReplacePolicy#NEVER} keeps bootstraps and background work on
     * the plain mismatch warning.
     */
    public void setHostKeyReplacePolicy(SshHostKeyTrustManager.ReplacePolicy replacePolicy) {
        this.hostKeyReplacePolicy = replacePolicy != null ? replacePolicy : SshHostKeyTrustManager.ReplacePolicy.NEVER;
    }
    
    /**
     * Why the last {@link #connect()} returned {@code false}, ready for display, or {@code null}.
     *
     * <p>A failure that returns {@code false} rather than throwing used to reach the log only, so
     * the terminal showed a generic "SSH connection failed" no matter what went wrong. This carries
     * the diagnosis across that boundary.
     */
    private volatile String lastFailureMessage;

    /** The owning tab's answered access reasons, or {@code null} outside a terminal tab. */
    private AccessReasonMemory accessReasonMemory;

    /** Prompts this attempt answered from {@link #accessReasonMemory} rather than by asking. */
    private final java.util.List<String> replayedAccessReasonPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Returns why the last {@link #connect()} call returned {@code false}, if a specific reason is
     * known. Empty when the failure carried no message or the call never ran.
     */
    public java.util.Optional<String> getLastFailureMessage() {
        String message = lastFailureMessage;
        return (message == null || message.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(message);
    }

    /**
     * Builds the user-facing text for a connection failure, appending the macOS Local Network
     * permission hint when that could be the cause. See {@link LocalNetworkDiagnostics}.
     */
    private String describeFailure(Throwable failure) {
        String base = failure.getMessage() != null && !failure.getMessage().isBlank()
            ? failure.getMessage()
            : failure.getClass().getSimpleName();
        return LocalNetworkDiagnostics.hintKeyFor(failure, connection.getHost())
            .map(key -> base + "\n\n" + I18n.get(key))
            .orElse(base);
    }

    /**
     * Initializes the SSH connection.
     * This should be called before start() on the terminal widget.
     */
    public boolean connect() throws AuthenticationException {
        SshHostKeyTrustManager.ConnectionVerifier hostKeyVerifier = null;
        lastFailureMessage = null;
        replayedAccessReasonPrompts.clear();
        sessionChangeTracker.reset();
        synchronized (directoryLock) {
            osc7BaselineHost = null;
            osc7Foreign = false;
        }
        try {
            logger.info("Connecting to {}@{}:{}", connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Create and start SSH client
            client = SshClient.setUpDefaultClient();
            // MINA's client default rejects every channel the server opens, which would refuse each
            // connection a remote tunnel delivers. Splits register no remote forwards, so for them
            // the filter admits nothing either.
            client.setForwardingFilter(SshTunnelManager.clientForwardingFilter());
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
                                    // Answered once per tab; a split replays the answer instead of
                                    // asking again. The reply is still sent — a server that asks
                                    // for a reason closes the connection on an empty one.
                                    final String reasonPrompt = prompt[i];
                                    finalResponses[i] = resolveAccessReason(instruction, reasonPrompt);
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
            
            SshHostKeyTrustManager.ReplacePolicy replacePolicy = hostKeyReplacePolicy;
            hostKeyVerifier = hostKeyTrustManager.verifierFor(
                connection, HostKeyCheckPolicy.resolveFromSettings(connection), replacePolicy);
            client.setServerKeyVerifier(hostKeyVerifier);
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

            // With an enabled jump server, hop first: authenticate to the bastion with its own
            // credentials and open a loopback forward to the target. The session below then
            // connects to that forward — but hostKeyVerifier was built for the target's real
            // host:port, so the target's key is still pinned under its real name, and a bastion
            // that answered with a different key would be rejected, not silently trusted.
            String connectHost = connection.getHost();
            int connectPort = connection.getPort();
            if (JumpHostSupport.isActive(connection)) {
                jumpTunnel = JumpHostSupport.open(
                    connection, hostKeyTrustManager, masterPassword, Duration.ofSeconds(timeoutSeconds),
                    replacePolicy);
                connectHost = jumpTunnel.localHost();
                connectPort = jumpTunnel.localPort();
                // Log raw host:port rather than connection.getDisplayName(): the latter can fall back
                // to "username@host", and CodeQL's coarse sensitive-data heuristic treats any getter on
                // ServerConnection as tainted once the class holds an encryptedPassword field. Host/port
                // carry no credential and give the same diagnostic value.
                logger.info("Connecting to {}:{} via jump server {}:{}",
                    connection.getHost(), connection.getPort(),
                    connection.getJumpServer().getHost(), connection.getJumpServer().getPort());
            }

            session = client.connect(username, connectHost, connectPort)
                    .verify(Duration.ofSeconds(timeoutSeconds))
                    .getSession();
            
            // Verify the session username is exactly what we set
            logger.debug("Session username after connect: '{}'", session.getUsername());
            
            // Clear any default key identity providers to avoid interference
            session.setKeyIdentityProvider(null);
            
            // Authenticate
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                try {
                    authenticateWithKey();
                } catch (AuthenticationException e) {
                    throw e;
                } catch (Exception e) {
                    // A key file that is missing or cannot be parsed is still missing on the next
                    // attempt, so this is not a connection failure to retry.
                    throw new AuthenticationException(e.getMessage(), e);
                }
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
            channel.setPtyType(TerminalEmulationSupport.termName(connection));
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
            if (!StandardCharsets.UTF_8.equals(charset)) {
                logger.info("Terminal encoding {} for {}:{}", charset.name(), connection.getHost(), connection.getPort());
            }

            writeShellStartupCommandIfConfigured();
            
            connected.set(true);
            logger.info("Connected to {}", connection.getDisplayName());
            
            // Start monitoring thread to detect disconnection
            startConnectionMonitor();
            // Actively probe the server so a silent transport death (network drop, server gone)
            // is detected within seconds instead of waiting for a TCP timeout.
            startLivenessProbe();

            return true;
            
        } catch (org.apache.sshd.common.SshException e) {
            if (hostKeyVerifier != null && hostKeyVerifier.wasRejected()) {
                logger.error("SSH host-key verification rejected connection to {}:{}",
                    connection.getHost(), connection.getPort());
                close();
                throw new HostKeyVerificationException(hostKeyRejectionMessage(), e);
            }
            // Check if this is an authentication error
            String message = e.getMessage();
            if (message != null && (message.contains("authentication") || 
                                    message.contains("No more authentication methods"))) {
                logger.error("Authentication failed for {}: {}", connection.getDisplayName(), message);
                // The refused reason may be exactly what failed here, so the tab must be able to
                // ask for a fresh one.
                forgetReplayedAccessReasons();
                close();
                // Throw a specific exception for auth failures - these should NOT be retried
                throw new AuthenticationException("Authentication failed: " + message, e);
            }
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            lastFailureMessage = describeFailure(e);
            close();
            return false;
        } catch (JumpHostSupport.PermanentJumpFailure e) {
            // The jump server cannot work as configured (locked vault, no stored password, missing
            // key, rejected bastion key): a retry would fail the same way, so stop here.
            // Host/port only in the log, for the CodeQL reason given at the jump hop above.
            logger.error("Jump server {}:{} for {}:{} cannot be used ({}) - not retrying: {}",
                connection.getJumpServer().getHost(), connection.getJumpServer().getPort(),
                connection.getHost(), connection.getPort(), e.kind(), e.getMessage());
            lastFailureMessage = e.getMessage();
            close();
            if (e.kind() == JumpHostSupport.PermanentJumpFailure.Kind.HOST_KEY_REJECTED) {
                throw new HostKeyVerificationException(hostKeyRejectionMessage(), e);
            }
            throw new ConnectionConfigurationException(e.getMessage(), e);
        } catch (AuthenticationException e) {
            // A rejected target host key still takes precedence, as in the other two branches: the
            // key exchange runs while the key file is loaded, and a refused key is the security signal.
            if (hostKeyVerifier != null && hostKeyVerifier.wasRejected()) {
                logger.error("SSH host-key verification rejected connection to {}:{}",
                    connection.getHost(), connection.getPort());
                close();
                throw new HostKeyVerificationException(hostKeyRejectionMessage(), e);
            }
            // Thrown before the server is asked (e.g. a key file that cannot be loaded); it is as
            // permanent as a refused login, so it must reach the caller instead of a retry.
            logger.error("Authentication for {}:{} cannot proceed: {}",
                connection.getHost(), connection.getPort(), e.getMessage());
            close();
            throw e;
        } catch (Exception e) {
            // A refused jump-server key arrives as PermanentJumpFailure above; a refused target key
            // is final too, since retrying would only show the same changed-key alert again.
            if (hostKeyVerifier != null && hostKeyVerifier.wasRejected()) {
                logger.error("SSH host-key verification rejected connection to {}:{}",
                    connection.getHost(), connection.getPort());
                close();
                throw new HostKeyVerificationException(hostKeyRejectionMessage(), e);
            }
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            lastFailureMessage = describeFailure(e);
            close();
            return false;
        }
    }

    private static String hostKeyRejectionMessage() {
        String key = "ssh.hostKey.connectionRejected";
        String localized = I18n.get(key);
        return localized.equals(key)
            ? "SSH host-key verification failed or was rejected."
            : localized;
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

    /** Non-retriable security failure which is distinct from user-authentication failure. */
    public static class HostKeyVerificationException extends AuthenticationException {
        public HostKeyVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Non-retriable failure of the connection's own setup, distinct from a refused login: for
     * example a jump server whose stored password cannot be decrypted while the vault is locked.
     * The message says what to fix and is meant to be shown as is.
     */
    public static class ConnectionConfigurationException extends AuthenticationException {
        public ConnectionConfigurationException(String message, Throwable cause) {
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
                    ChannelCloseClassification classification =
                        classifyChannelClose(channel.getExitStatus(), channel.getExitSignal());

                    logger.info("SSH connection ended: {} (wasError={})",
                        classification.reason(), classification.wasError());

                    // Notify listener
                    if (disconnectListener != null) {
                        javafx.application.Platform.runLater(() -> {
                            disconnectListener.onDisconnect(classification.reason(), classification.wasError());
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

    /**
     * How quickly a dead transport is noticed: a probe runs every {@link #LIVENESS_PROBE_INTERVAL_MS}
     * and a missing reply is confirmed by a second probe, so the worst case is
     * interval + 2 * timeout. Both values are chosen so that stays within 10 seconds.
     */
    static final long LIVENESS_PROBE_INTERVAL_MS = 3_000;
    static final long LIVENESS_PROBE_TIMEOUT_MS = 3_000;
    /** Global request the server must answer (any reply, including failure, proves liveness). */
    private static final String LIVENESS_REQUEST_NAME = "keepalive@kortty.de";

    /**
     * Periodically sends an SSH global request and waits for the reply, mirroring OpenSSH's
     * {@code keepalive@openssh.com} liveness check. A dead network leaves the request unanswered,
     * which is detected within seconds; TCP alone would take minutes to notice. On a confirmed
     * death the transport is closed, which wakes the connection monitor and reports the loss.
     *
     * <p>The kill-switch only arms after the server answered one probe: a server that never
     * replies to global requests (violating RFC 4254) must not have healthy sessions killed.</p>
     */
    private void startLivenessProbe() {
        livenessProbeThread = new Thread(() -> {
            boolean armed = false;
            while (connected.get()) {
                try {
                    Thread.sleep(LIVENESS_PROBE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!connected.get()) {
                    return;
                }
                ClientSession currentSession = session;
                if (currentSession == null || !currentSession.isOpen()) {
                    return; // already closing: the connection monitor reports it
                }
                if (probeServer(currentSession)) {
                    armed = true;
                    continue;
                }
                if (!armed || !connected.get()) {
                    continue;
                }
                if (probeServer(currentSession)) {
                    continue; // single missed reply: not yet a death
                }
                if (!connected.get()) {
                    return;
                }
                logger.warn("SSH liveness probe got no reply twice for {} - treating connection as lost",
                    connection.getDisplayName());
                forceCloseDeadTransport(currentSession);
                return;
            }
        }, "SSH-Liveness-" + connection.getDisplayName());
        livenessProbeThread.setDaemon(true);
        livenessProbeThread.start();
    }

    /** Sends one probe; true when the server replied (success or failure), false on timeout/error. */
    private boolean probeServer(ClientSession currentSession) {
        try {
            Buffer buffer = currentSession.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST,
                LIVENESS_REQUEST_NAME.length() + Byte.SIZE);
            buffer.putString(LIVENESS_REQUEST_NAME);
            buffer.putBoolean(true); // want-reply
            // Returns the reply buffer on success and null on SSH_MSG_REQUEST_FAILURE - both prove
            // the server is alive. Timeouts and transport errors surface as IOExceptions.
            currentSession.request(LIVENESS_REQUEST_NAME, buffer, LIVENESS_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.debug("SSH liveness probe got no reply for {}: {}",
                connection.getDisplayName(), e.getMessage());
            return false;
        }
    }

    /**
     * Closes channel and session of a dead transport immediately (no graceful close handshake -
     * the peer is unreachable). Closing the channel wakes {@link #startConnectionMonitor()}'s
     * waitFor, which classifies the missing exit status as "Connection lost".
     */
    private void forceCloseDeadTransport(ClientSession deadSession) {
        try {
            if (channel != null) {
                channel.close(true);
            }
        } catch (Exception e) {
            logger.debug("Error closing channel of dead transport: {}", e.getMessage());
        }
        try {
            deadSession.close(true);
        } catch (Exception e) {
            logger.debug("Error closing dead session: {}", e.getMessage());
        }
    }

    /** How a closed shell channel should be reported to the {@link DisconnectListener}. */
    record ChannelCloseClassification(String reason, boolean wasError) {
    }

    /**
     * Classifies a closed shell channel from its remote exit status/signal. A channel that closed
     * without either means the remote shell never reported an exit: the transport itself died
     * (network drop, server gone). That is reported as an error so the tab stays open and offers a
     * reconnect instead of silently closing like a normal logout.
     */
    static ChannelCloseClassification classifyChannelClose(Integer exitStatus, String exitSignal) {
        if (exitSignal != null && !exitSignal.isEmpty()) {
            return new ChannelCloseClassification("Connection terminated with signal: " + exitSignal, true);
        }
        if (exitStatus != null && exitStatus != 0) {
            return new ChannelCloseClassification("Connection closed with exit code: " + exitStatus, true);
        }
        if (exitStatus != null) {
            return new ChannelCloseClassification("Normal exit", false);
        }
        return new ChannelCloseClassification("Connection lost", true);
    }

    @Override
    public boolean wasConnectionLost() {
        ChannelShell currentChannel = channel;
        if (currentChannel == null || currentChannel.isOpen()) {
            return false;
        }
        Integer exitStatus = currentChannel.getExitStatus();
        String exitSignal = currentChannel.getExitSignal();
        return exitStatus == null && (exitSignal == null || exitSignal.isEmpty());
    }

    @Override
    public void close() {
        connected.set(false);
        
        // Stop monitor threads
        if (connectionMonitorThread != null) {
            connectionMonitorThread.interrupt();
        }
        if (livenessProbeThread != null) {
            livenessProbeThread.interrupt();
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
            if (jumpTunnel != null) {
                jumpTunnel.close();
                jumpTunnel = null;
            }
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
                notifyInputActivity(bytesToWrite.length);
                trackPotentialDirectoryChange(bytesToWrite);
                outputStream.write(bytesToWrite);
                outputStream.flush();
            }
        }
    }

    private void notifyInputActivity(int byteCount) {
        for (InputActivityListener listener : inputActivityListeners) {
            try {
                listener.onInputActivity(byteCount);
            } catch (Exception e) {
                logger.warn("Input activity listener error: {}", e.getMessage());
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

    public void addInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.addIfAbsent(listener);
        }
    }

    public void removeInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.remove(listener);
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
     * The value is updated passively from terminal output (OSC 7) and typed
     * directory-changing commands, so login output stays visible to the user.
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
        updateCurrentRemoteDirectoryHint(directory, RemoteDirectoryChange.Source.HOME_HINT);
    }

    private void updateCurrentRemoteDirectoryHint(String directory, RemoteDirectoryChange.Source source) {
        String resolved = resolveRemoteDirectoryHint(directory, getHomeRemoteDirectory());
        if (resolved != null) {
            setCurrentRemoteDirectory(resolved, source, null);
        }
    }

    /**
     * True when typed input suggests a nested login ({@code su}, {@code ssh}, a shell-opening
     * {@code sudo}) or an OSC 7 report named a host other than the session's baseline OSC 7 host.
     */
    @Override
    public boolean isForeignSessionSuspected() {
        if (sessionChangeTracker.isForeignSessionSuspected()) {
            return true;
        }
        synchronized (directoryLock) {
            return osc7Foreign;
        }
    }

    /**
     * Registers a listener for changes of the tracked directory. It runs on the thread that saw the
     * change (output reader or input path), after the directory lock is released and only for an
     * actual change; it must hand off and never block. A throwing listener is logged and skipped.
     */
    @Override
    public RemoteDirectoryChange.Subscription addRemoteDirectoryListener(RemoteDirectoryChange.Listener listener) {
        if (listener == null) {
            return RemoteDirectoryChange.Subscription.NONE;
        }
        remoteDirectoryListeners.add(listener);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                remoteDirectoryListeners.remove(listener);
            }
        };
    }

    private void notifyRemoteDirectoryListeners(RemoteDirectoryChange change) {
        for (RemoteDirectoryChange.Listener listener : remoteDirectoryListeners) {
            try {
                listener.onRemoteDirectoryChanged(change);
            } catch (RuntimeException e) {
                logger.warn("Remote directory listener error: {}", e.toString());
            }
        }
    }

    @Override
    public void confirmNativeSessionIdentity() {
        sessionChangeTracker.confirmNativeIdentity();
    }

    @Override
    public String getExpectedSessionUser() {
        return connection.getUsername();
    }

    @Override
    public String getExpectedSessionHost() {
        return connection.getHost();
    }

    public void updateHomeRemoteDirectoryHint(String directory) {
        String resolved = resolveRemoteDirectoryHint(directory, null);
        if (resolved == null) {
            return;
        }
        boolean currentChanged = false;
        synchronized (directoryLock) {
            homeRemoteDirectory = resolved;
            if (currentRemoteDirectory == null
                    || currentRemoteDirectory.isBlank()
                    || "~".equals(currentRemoteDirectory)) {
                currentChanged = !resolved.equals(currentRemoteDirectory);
                currentRemoteDirectory = resolved;
            }
            if (previousRemoteDirectory == null
                    || previousRemoteDirectory.isBlank()
                    || "~".equals(previousRemoteDirectory)) {
                previousRemoteDirectory = resolved;
            }
        }
        if (currentChanged) {
            notifyRemoteDirectoryListeners(
                new RemoteDirectoryChange(resolved, RemoteDirectoryChange.Source.HOME_HINT, null));
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
        // Only the shell startup hook prints korTTY-agent sequences. Without it they can only be
        // remote output, which must not move the directory that uploads and agent runs start in.
        if (!hasShellStartupCommand()) {
            return;
        }
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
                    setCurrentRemoteDirectory(cwd, RemoteDirectoryChange.Source.AGENT_HOOK, null);
                    logger.debug("Updated remote directory from terminal agent hook: {}", cwd);
                }
            }
        }
    }

    private void updateCurrentDirectoryFromOsc7(String uriText) {
        Osc7Location location = parseOsc7Uri(uriText);
        if (location == null || location.path().isBlank()) {
            logger.debug("Failed to parse OSC 7 URI '{}'", uriText);
            return;
        }
        if (!adoptOsc7Host(location.host())) {
            logger.debug("Ignored OSC 7 from host '{}': not the session's host", location.host());
            return;
        }
        setCurrentRemoteDirectory(location.path(), RemoteDirectoryChange.Source.OSC7, location.host());
        logger.debug("Updated remote directory from OSC 7: {}", location.path());
    }

    /**
     * Decides whether an OSC 7 report with {@code host} may move the tracked directory, and keeps
     * the sticky foreign flag. {@code ServerConnection.getHost()} cannot serve as the reference: it
     * is often an IP, an ssh alias, a jump or CyberArk proxy, or an FQDN where {@code hostname}
     * prints a short name. The first OSC 7 host seen while the session is native becomes the
     * baseline instead. An empty or localhost host is always adopted and never changes the
     * verdict; another host than the baseline is refused and marks the session foreign until an OSC
     * 7 with the baseline host arrives or the connector reconnects.
     */
    private boolean adoptOsc7Host(String host) {
        if (isLocalOsc7Host(host)) {
            return true;
        }
        synchronized (directoryLock) {
            if (osc7BaselineHost == null) {
                if (!sessionChangeTracker.isForeignSessionSuspected()) {
                    osc7BaselineHost = host;
                }
                return true;
            }
            if (sameOsc7Host(osc7BaselineHost, host)) {
                osc7Foreign = false;
                return true;
            }
            osc7Foreign = true;
            return false;
        }
    }

    static boolean isLocalOsc7Host(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String key = osc7HostKey(host);
        return key.equals("localhost") || key.equals("127.0.0.1") || key.equals("::1") || key.equals("[::1]");
    }

    /** Case-insensitive comparison on the short name, so {@code web01} matches {@code web01.example.com}. */
    static boolean sameOsc7Host(String left, String right) {
        return osc7HostKey(left).equals(osc7HostKey(right));
    }

    private static String osc7HostKey(String host) {
        String lower = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || lower.contains(":") || lower.matches("[0-9.]+")) {
            return lower; // IP literals compare whole
        }
        int dot = lower.indexOf('.');
        return dot > 0 ? lower.substring(0, dot) : lower;
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
                } else if (ch == 0x04) { // Ctrl-D on an empty line ends the innermost shell
                    if (inputLineBuffer.length() == 0) {
                        sessionChangeTracker.onEndOfFileOnEmptyLine();
                    }
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
        sessionChangeTracker.onSubmittedLine(inputLine);
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
            updateCurrentRemoteDirectoryHint("~", RemoteDirectoryChange.Source.TYPED_CD);
            return;
        }
        String target = unquote(arg);
        if ("-".equals(target)) {
            setCurrentRemoteDirectory(previousRemoteDirectory, RemoteDirectoryChange.Source.TYPED_CD, null);
            return;
        }
        String homeResolved = resolveRemoteDirectoryHint(target, getHomeRemoteDirectory());
        if (homeResolved != null) {
            setCurrentRemoteDirectory(homeResolved, RemoteDirectoryChange.Source.TYPED_CD, null);
            return;
        }
        if (isTildeRemoteDirectoryHint(target)) {
            return;
        }
        if (target.startsWith("/")) {
            setCurrentRemoteDirectory(normalizeRemotePath(target), RemoteDirectoryChange.Source.TYPED_CD, null);
            return;
        }
        setCurrentRemoteDirectory(normalizeRemotePath(currentRemoteDirectory + "/" + target), RemoteDirectoryChange.Source.TYPED_CD, null);
    }

    private void applyPushdCommand(String segment) {
        String arg = segment.length() <= 5 ? "" : segment.substring(5).trim();
        if (arg.isEmpty()) {
            String stacked = directoryStack.pollFirst();
            if (stacked != null) {
                directoryStack.addFirst(currentRemoteDirectory);
                setCurrentRemoteDirectory(stacked, RemoteDirectoryChange.Source.TYPED_CD, null);
            }
            return;
        }
        directoryStack.addFirst(currentRemoteDirectory);
        String target = unquote(arg);
        String homeResolved = resolveRemoteDirectoryHint(target, getHomeRemoteDirectory());
        if (homeResolved != null) {
            setCurrentRemoteDirectory(homeResolved, RemoteDirectoryChange.Source.TYPED_CD, null);
        } else if (isTildeRemoteDirectoryHint(target)) {
            return;
        } else if (target.startsWith("/")) {
            setCurrentRemoteDirectory(normalizeRemotePath(target), RemoteDirectoryChange.Source.TYPED_CD, null);
        } else {
            setCurrentRemoteDirectory(normalizeRemotePath(currentRemoteDirectory + "/" + target), RemoteDirectoryChange.Source.TYPED_CD, null);
        }
    }

    private void applyPopdCommand() {
        String stacked = directoryStack.pollFirst();
        if (stacked != null) {
            setCurrentRemoteDirectory(stacked, RemoteDirectoryChange.Source.TYPED_CD, null);
        }
    }

    private void setCurrentRemoteDirectory(String newDirectory, RemoteDirectoryChange.Source source, String osc7Host) {
        if (newDirectory == null || newDirectory.isBlank()) {
            return;
        }
        String normalized = normalizeRemotePath(newDirectory);
        if (normalized.isBlank()) {
            return;
        }
        boolean changed = false;
        synchronized (directoryLock) {
            if (!normalized.equals(currentRemoteDirectory)) {
                previousRemoteDirectory = currentRemoteDirectory;
                currentRemoteDirectory = normalized;
                changed = true;
                logger.debug("Tracked remote directory updated to {}", normalized);
            }
        }
        // Listeners run outside the directory lock, so one that reads the directory back cannot deadlock.
        if (changed) {
            notifyRemoteDirectoryListeners(new RemoteDirectoryChange(normalized, source, osc7Host));
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

    static String resolveRemoteDirectoryHint(String directory, String homeDirectory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        String trimmed = directory.trim();
        if (trimmed.startsWith("/")) {
            return normalizeAbsoluteRemotePath(trimmed);
        }
        if (isTildeRemoteDirectoryHint(trimmed)) {
            if (homeDirectory == null || !homeDirectory.startsWith("/")) {
                return null;
            }
            if ("~".equals(trimmed)) {
                return normalizeAbsoluteRemotePath(homeDirectory);
            }
            if (trimmed.startsWith("~/")) {
                return normalizeAbsoluteRemotePath(homeDirectory + trimmed.substring(1));
            }
        }
        return null;
    }

    private static boolean isTildeRemoteDirectoryHint(String directory) {
        return directory != null && directory.trim().startsWith("~");
    }

    private static String normalizeAbsoluteRemotePath(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
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
        StringBuilder result = new StringBuilder("/");
        boolean first = true;
        for (String part : normalized) {
            if (!first) {
                result.append('/');
            }
            result.append(part);
            first = false;
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
        Osc7Location location = parseOsc7Uri(uriText);
        return location != null ? location.path() : null;
    }

    /**
     * The host and path of an OSC 7 {@code file://host/path} report.
     *
     * @param host the authority, lower-case; empty when the URI named none
     * @param path the decoded absolute path
     */
    record Osc7Location(String host, String path) {
    }

    /** Parses an OSC 7 URI into host and path; {@code null} when it is not a {@code file:} URI with an absolute path. */
    static Osc7Location parseOsc7Uri(String uriText) {
        if (uriText == null || uriText.isBlank()) {
            return null;
        }
        String filePrefix = "file://";
        try {
            URI uri = URI.create(uriText);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/")) {
                return null;
            }
            String authority = uri.getRawAuthority();
            return new Osc7Location(normalizeOsc7Host(authority), path);
        } catch (IllegalArgumentException e) {
            if (!uriText.regionMatches(true, 0, filePrefix, 0, filePrefix.length())) {
                return null;
            }
            int pathStart = uriText.indexOf('/', filePrefix.length());
            if (pathStart < 0) {
                return null;
            }
            String path = uriText.substring(pathStart);
            return new Osc7Location(normalizeOsc7Host(uriText.substring(filePrefix.length(), pathStart)), path);
        }
    }

    /** Lower-cases the authority and drops any user info and port; never {@code null}. */
    private static String normalizeOsc7Host(String authority) {
        if (authority == null) {
            return "";
        }
        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (!host.startsWith("[")) {
            int colon = host.indexOf(':');
            if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
                host = host.substring(0, colon);
            }
        }
        return host.trim().toLowerCase(java.util.Locale.ROOT);
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
        
        // A temporary SSH key is parsed in memory and never written to disk.
        if (TemporarySshKeyMaterial.isTemporaryKeyPath(keyPath)) {
            try {
                java.security.KeyPair keyPair = TemporarySshKeyMaterial.load(session, keyPath).get(0);
                // Offer ONLY the temporary key, so no other identity interferes with authentication.
                session.setKeyIdentityProvider(null);
                session.addPublicKeyIdentity(keyPair);
                logger.info("Using temporary SSH key (algorithm: {})", keyPair.getPublic().getAlgorithm());
                return;
            } catch (Exception e) {
                logger.error("Failed to load temporary SSH key", e);
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
    /**
     * Answers an access-reason prompt from the owning tab's memory when it already holds one, and
     * otherwise by asking the user with {@link #showAccessReasonDialog}.
     */
    String resolveAccessReason(String instruction, String promptText) {
        if (accessReasonMemory == null) {
            return showAccessReasonDialog(instruction, promptText);
        }
        String target = accessReasonTarget();
        if (accessReasonMemory.remembers(target, promptText)) {
            replayedAccessReasonPrompts.add(promptText);
        }
        return accessReasonMemory.answer(
            target, promptText, () -> showAccessReasonDialog(instruction, promptText));
    }

    /**
     * Drops the answers this attempt replayed, so the next one asks again. Only an authentication
     * failure gets here: a reason can go stale while its tab stays open, but a dropped network is
     * not the reason's fault, and forgetting one there would put the dialog back in front of every
     * automatic reconnect.
     */
    private void forgetReplayedAccessReasons() {
        if (accessReasonMemory == null || replayedAccessReasonPrompts.isEmpty()) {
            return;
        }
        String target = accessReasonTarget();
        for (String prompt : replayedAccessReasonPrompts) {
            accessReasonMemory.forget(target, prompt);
        }
        replayedAccessReasonPrompts.clear();
    }

    /** @return the prompt's connection as {@code user@host:port}, the memory's per-target key. */
    String accessReasonTarget() {
        return connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort();
    }

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
        return TemporarySshKeyMaterial.isTemporaryKeyPath(connection.getPrivateKeyPath());
    }

    private boolean isPasswordPrompt(String promptText) {
        return PasswordPromptDetector.isPasswordPrompt(promptText);
    }

    
}
