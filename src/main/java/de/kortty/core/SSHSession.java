package de.kortty.core;

import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Represents an active SSH session.
 */
public class SSHSession {
    
    private static final Logger logger = LoggerFactory.getLogger(SSHSession.class);
    
    private final String sessionId;
    private final ServerConnection connection;
    private final String password;
    
    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    
    private PipedInputStream terminalInput;
    private PipedOutputStream terminalOutput;
    private PipedInputStream channelInput;
    private PipedOutputStream channelOutput;
    
    private final StringBuilder terminalBuffer = new StringBuilder();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final LocalDateTime connectedAt;
    
    private String currentDirectory = "~";
    private String currentApplication = null;
    private Consumer<String> outputConsumer;
    
    private Thread readerThread;
    
    public SSHSession(String sessionId, ServerConnection connection, String password) {
        this.sessionId = sessionId;
        this.connection = connection;
        this.password = password;
        this.connectedAt = LocalDateTime.now();
    }
    
    /**
     * Establishes the SSH connection.
     */
    public void connect() throws Exception {
        logger.info("Connecting to {}@{}:{}", 
                connection.getUsername(), connection.getHost(), connection.getPort());
        
        client = SshClient.setUpDefaultClient();
        client.start();
        
        session = client.connect(
                connection.getUsername(),
                connection.getHost(),
                connection.getPort()
        ).verify(Duration.ofSeconds(30)).getSession();
        
        // Authenticate
        switch (connection.getAuthMethod()) {
            case PASSWORD:
                session.addPasswordIdentity(password);
                break;
            case PUBLIC_KEY:
                // TODO: Add key authentication
                break;
            case KEYBOARD_INTERACTIVE:
                session.addPasswordIdentity(password);
                break;
        }
        
        session.auth().verify(Duration.ofSeconds(30));
        
        // Open shell channel
        channel = session.createShellChannel();
        
        // Set up PTY
        channel.setPtyType("xterm-256color");
        channel.setPtyColumns(connection.getSettings().getTerminalColumns());
        channel.setPtyLines(connection.getSettings().getTerminalRows());
        
        // Set up streams for communication with the channel
        // We need:
        // - An InputStream for the channel to read our input (what we type)
        // - An OutputStream for the channel to write its output (what we see)
        
        // For input: We write to channelOutput, channel reads from terminalInput
        terminalInput = new PipedInputStream(8192);
        channelOutput = new PipedOutputStream(terminalInput);
        
        // For output: Channel writes to terminalOutput, we read from channelInput
        channelInput = new PipedInputStream(8192);
        terminalOutput = new PipedOutputStream(channelInput);
        
        channel.setIn(terminalInput);
        channel.setOut(terminalOutput);
        channel.setErr(terminalOutput);
        
        channel.open().verify(Duration.ofSeconds(10));
        
        connected.set(true);
        startReaderThread();
        
        logger.info("Connected to {}", connection.getDisplayName());
    }
    
    /**
     * Starts a thread to read output from the SSH channel.
     */
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                while (connected.get() && !Thread.currentThread().isInterrupted()) {
                    // Use blocking read - will block until data is available
                    int read = channelInput.read(buffer);
                    if (read > 0) {
                        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        synchronized (terminalBuffer) {
                            terminalBuffer.append(text);
                        }
                        if (outputConsumer != null) {
                            outputConsumer.accept(text);
                        }
                    } else if (read == -1) {
                        // End of stream
                        logger.info("SSH channel closed");
                        connected.set(false);
                        break;
                    }
                }
            } catch (IOException e) {
                if (connected.get()) {
                    logger.error("Error reading from channel", e);
                }
            }
            logger.debug("Reader thread for session {} stopped", sessionId);
        }, "SSH-Reader-" + sessionId);
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    /**
     * Sends input to the SSH channel.
     */
    public void sendInput(String input) throws IOException {
        if (connected.get() && channelOutput != null) {
            channelOutput.write(input.getBytes(StandardCharsets.UTF_8));
            channelOutput.flush();
        }
    }
    
    /**
     * Sends a single character to the SSH channel.
     */
    public void sendChar(char c) throws IOException {
        sendInput(String.valueOf(c));
    }
    
    /**
     * Sends special key sequences.
     */
    public void sendSpecialKey(SpecialKey key) throws IOException {
        sendInput(key.getSequence());
    }
    
    /**
     * Resizes the terminal.
     */
    public void resize(int columns, int rows) {
        if (channel != null && connected.get()) {
            try {
                channel.sendWindowChange(columns, rows);
                logger.debug("Resized terminal to {}x{}", columns, rows);
            } catch (IOException e) {
                logger.error("Failed to resize terminal", e);
            }
        }
    }
    
    /**
     * Disconnects the SSH session.
     */
    public void disconnect() {
        connected.set(false);
        
        if (readerThread != null) {
            readerThread.interrupt();
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
        } catch (IOException e) {
            logger.error("Error disconnecting", e);
        }
        
        logger.info("Disconnected from {}", connection.getDisplayName());
    }
    
    /**
     * Gets the current session state for saving.
     */
    public SessionState getState() {
        SessionState state = new SessionState(sessionId, connection.getId());
        state.setCurrentDirectory(currentDirectory);
        state.setCurrentApplication(currentApplication);
        state.setTabTitle(generateTabTitle());
        synchronized (terminalBuffer) {
            state.setTerminalHistory(terminalBuffer.toString());
        }
        return state;
    }
    
    /**
     * Restores terminal history from saved state.
     */
    public void restoreHistory(String history) {
        if (history != null) {
            synchronized (terminalBuffer) {
                terminalBuffer.setLength(0);
                terminalBuffer.append(history);
            }
            if (outputConsumer != null) {
                outputConsumer.accept(history);
            }
        }
    }
    
    /**
     * Generates a tab title based on current state.
     */
    public String generateTabTitle() {
        String username = connection.getUsername();
        if (currentApplication != null && !currentApplication.isBlank()) {
            return username + " @ " + currentApplication;
        }
        return username + " @ " + shortenPath(currentDirectory);
    }
    
    private String shortenPath(String path) {
        if (path == null || path.length() <= 15) {
            return path;
        }
        String[] parts = path.split("/");
        if (parts.length <= 2) {
            return path;
        }
        return ".../" + parts[parts.length - 1];
    }
    
    // Getters and Setters
    
    public String getSessionId() {
        return sessionId;
    }
    
    public ServerConnection getConnection() {
        return connection;
    }
    
    public boolean isConnected() {
        return connected.get();
    }
    
    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }
    
    public String getTerminalBuffer() {
        synchronized (terminalBuffer) {
            return terminalBuffer.toString();
        }
    }
    
    public long getBufferedTextSize() {
        synchronized (terminalBuffer) {
            return terminalBuffer.length();
        }
    }
    
    public void setOutputConsumer(Consumer<String> outputConsumer) {
        this.outputConsumer = outputConsumer;
    }
    
    public String getCurrentDirectory() {
        return currentDirectory;
    }
    
    public void setCurrentDirectory(String currentDirectory) {
        this.currentDirectory = currentDirectory;
    }
    
    public String getCurrentApplication() {
        return currentApplication;
    }
    
    public void setCurrentApplication(String currentApplication) {
        this.currentApplication = currentApplication;
    }
    
    /**
     * Special key sequences.
     */
    public enum SpecialKey {
        ENTER("\r"),
        TAB("\t"),
        BACKSPACE("\u007F"),
        ESCAPE("\u001B"),
        UP("\u001B[A"),
        DOWN("\u001B[B"),
        RIGHT("\u001B[C"),
        LEFT("\u001B[D"),
        HOME("\u001B[H"),
        END("\u001B[F"),
        PAGE_UP("\u001B[5~"),
        PAGE_DOWN("\u001B[6~"),
        INSERT("\u001B[2~"),
        DELETE("\u001B[3~"),
        F1("\u001BOP"),
        F2("\u001BOQ"),
        F3("\u001BOR"),
        F4("\u001BOS"),
        F5("\u001B[15~"),
        F6("\u001B[17~"),
        F7("\u001B[18~"),
        F8("\u001B[19~"),
        F9("\u001B[20~"),
        F10("\u001B[21~"),
        F11("\u001B[23~"),
        F12("\u001B[24~");
        
        private final String sequence;
        
        SpecialKey(String sequence) {
            this.sequence = sequence;
        }
        
        public String getSequence() {
            return sequence;
        }
    }
}
