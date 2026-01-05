package de.kortty.core;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
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
            session = client.connect(connection.getUsername(), connection.getHost(), connection.getPort())
                    .verify(Duration.ofSeconds(timeoutSeconds))
                    .getSession();
            
            // Authenticate
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                authenticateWithKey();
            } else {
                session.addPasswordIdentity(password);
            }
            session.auth().verify(Duration.ofSeconds(timeoutSeconds));
            
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
