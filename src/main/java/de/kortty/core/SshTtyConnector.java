package de.kortty.core;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TtyConnector implementation for SSH connections using Apache MINA SSHD.
 * This connector integrates with JediTermFX for terminal emulation.
 */
public class SshTtyConnector implements TtyConnector {
    
    private static final Logger logger = LoggerFactory.getLogger(SshTtyConnector.class);
    
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
    
    private DisconnectListener disconnectListener;
    private Thread connectionMonitorThread;
    private DataListener dataListener;
    
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
    public boolean connect() {
        try {
            logger.info("Connecting to {}@{}:{}", connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Create and start SSH client
            client = SshClient.setUpDefaultClient();
            
            // Enable public key AND keyboard-interactive authentication
            // CyberArk requires keyboard-interactive AFTER publickey for access reason
            client.setUserAuthFactories(java.util.Arrays.asList(
                new UserAuthPublicKeyFactory(),
                new UserAuthKeyboardInteractiveFactory()
            ));
            
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
                                
                                // Create dialog for each prompt
                                javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
                                dialog.setTitle("SSH Authentication");
                                dialog.setHeaderText(instruction != null && !instruction.isEmpty() ? instruction : "Authentication Required");
                                dialog.setContentText(prompt[i]);
                                
                                // If echo is false, we should use a password field, but TextInputDialog doesn't support that
                                // For now, we'll use the regular text field
                                
                                java.util.Optional<String> result = dialog.showAndWait();
                                finalResponses[i] = result.orElse("");
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
            ptyModes.put(PtyMode.ECHO, 1);
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
            
            connected.set(true);
            logger.info("Connected to {}", connection.getDisplayName());
            
            // Start monitoring thread to detect disconnection
            startConnectionMonitor();
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
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
        }
        logger.info("Disconnected from {}", connection.getDisplayName());
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
        int count = reader.read(buf, offset, length);
        
        // Notify listener of received data
        if (count > 0 && dataListener != null) {
            try {
                String data = new String(buf, offset, count);
                dataListener.onData(data);
            } catch (Exception e) {
                // Don't let listener errors break the connection
                logger.warn("Data listener error: {}", e.getMessage());
            }
        }
        
        return count;
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
                logger.debug("Resized terminal to {}x{}", termSize.getColumns(), termSize.getRows());
            } catch (Exception e) {
                logger.warn("Failed to resize terminal: {}", e.getMessage());
            }
        }
    }
    
    public void setDisconnectListener(DisconnectListener listener) {
        this.disconnectListener = listener;
    }
    
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }
    
    public ServerConnection getConnection() {
        return connection;
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
                
                // DEBUG: Save a copy to a known location for inspection
                java.io.File debugFile = new java.io.File(System.getProperty("user.home"), ".kortty/debug_temp_key.pem");
                try (java.io.FileWriter debugWriter = new java.io.FileWriter(debugFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    debugWriter.write(keyContentFixed);
                    // Set permissions to 600 (owner read/write only)
                    try {
                        java.nio.file.Files.setPosixFilePermissions(
                            debugFile.toPath(),
                            java.util.Set.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                            )
                        );
                    } catch (UnsupportedOperationException permEx) {
                        // Windows fallback
                        debugFile.setReadable(false, false);
                        debugFile.setReadable(true, true);
                        debugFile.setWritable(false, false);
                        debugFile.setWritable(true, true);
                    }
                    logger.info("DEBUG: Saved temporary key to {} for inspection (chmod 600)", debugFile.getAbsolutePath());
                } catch (Exception debugEx) {
                    logger.warn("DEBUG: Could not save debug key file: {}", debugEx.getMessage());
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
        
        // Use passphrase from manager if available, otherwise from connection
        String passphrase = passphraseRef[0];
        if (passphrase == null) {
            passphrase = connection.getPrivateKeyPassphrase();
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
     * Listener for data received from the SSH connection.
     */
    public interface DataListener {
        void onData(String data);
    }
}
