package de.kortty.core;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyMode;
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
    
    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private InputStreamReader reader;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Charset charset = StandardCharsets.UTF_8;
    
    public SshTtyConnector(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
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
            
            // Connect to server
            session = client.connect(connection.getUsername(), connection.getHost(), connection.getPort())
                    .verify(Duration.ofSeconds(30))
                    .getSession();
            
            // Authenticate
            session.addPasswordIdentity(password);
            session.auth().verify(Duration.ofSeconds(30));
            
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
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to connect to {}: {}", connection.getDisplayName(), e.getMessage(), e);
            close();
            return false;
        }
    }
    
    @Override
    public void close() {
        connected.set(false);
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
    
    public ServerConnection getConnection() {
        return connection;
    }
}
