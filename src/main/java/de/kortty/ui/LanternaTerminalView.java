package de.kortty.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.swing.ScrollingSwingTerminal;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorColorConfiguration;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorDeviceConfiguration;
import de.kortty.core.SshTtyConnector;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.scene.layout.BorderPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal view using Lanterna library for better zoom support.
 */
public class LanternaTerminalView extends BorderPane {
    
    private static final Logger logger = LoggerFactory.getLogger(LanternaTerminalView.class);
    
    private final ServerConnection connection;
    private final String password;
    private final ConnectionSettings settings;
    
    private ScrollingSwingTerminal terminalPanel;
    private SwingNode swingNode;
    private SshTtyConnector ttyConnector;
    
    private int currentFontSize;
    private final int defaultFontSize;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 48;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;
    
    public LanternaTerminalView(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
        this.settings = connection.getSettings() != null ? connection.getSettings() : new ConnectionSettings();
        this.defaultFontSize = settings.getFontSize();
        this.currentFontSize = defaultFontSize;
        
        initializeTerminal();
    }
    
    private void initializeTerminal() {
        swingNode = new SwingNode();
        
        SwingUtilities.invokeLater(() -> {
            terminalPanel = createTerminalPanel();
            swingNode.setContent(terminalPanel);
            
            terminalPanel.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    handleKeyPress(e);
                }
            });
            
            terminalPanel.setFocusable(true);
            terminalPanel.requestFocusInWindow();
        });
        
        setCenter(swingNode);
        Platform.runLater(() -> swingNode.requestFocus());
    }
    
    private ScrollingSwingTerminal createTerminalPanel() {
        Font terminalFont = new Font(settings.getFontFamily(), Font.PLAIN, currentFontSize);
        SwingTerminalFontConfiguration fontConfig = SwingTerminalFontConfiguration.newInstance(terminalFont);
        TerminalEmulatorColorConfiguration colorConfig = TerminalEmulatorColorConfiguration.getDefault();
        TerminalEmulatorDeviceConfiguration deviceConfig = TerminalEmulatorDeviceConfiguration.getDefault();
        
        ScrollingSwingTerminal terminal = new ScrollingSwingTerminal(
                deviceConfig,
                fontConfig,
                colorConfig
        );
        
        terminal.setBackground(Color.BLACK);
        terminal.setForeground(Color.WHITE);
        
        return terminal;
    }
    
    private void handleKeyPress(KeyEvent e) {
        if (ttyConnector == null || !ttyConnector.isConnected()) return;
        
        try {
            String toSend = null;
            
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER: toSend = "\r"; break;
                case KeyEvent.VK_BACK_SPACE: toSend = "\u007F"; break;
                case KeyEvent.VK_TAB: toSend = "\t"; break;
                case KeyEvent.VK_ESCAPE: toSend = "\u001B"; break;
                case KeyEvent.VK_UP: toSend = "\u001B[A"; break;
                case KeyEvent.VK_DOWN: toSend = "\u001B[B"; break;
                case KeyEvent.VK_RIGHT: toSend = "\u001B[C"; break;
                case KeyEvent.VK_LEFT: toSend = "\u001B[D"; break;
                case KeyEvent.VK_HOME: toSend = "\u001B[H"; break;
                case KeyEvent.VK_END: toSend = "\u001B[F"; break;
                case KeyEvent.VK_DELETE: toSend = "\u001B[3~"; break;
                default:
                    if (e.isControlDown() && e.getKeyChar() != KeyEvent.CHAR_UNDEFINED) {
                        char c = e.getKeyChar();
                        if (c < 32) toSend = String.valueOf(c);
                    } else if (e.getKeyChar() != KeyEvent.CHAR_UNDEFINED && !e.isActionKey()) {
                        toSend = String.valueOf(e.getKeyChar());
                    }
            }
            
            if (toSend != null) ttyConnector.write(toSend);
        } catch (IOException ex) {
            logger.error("Error sending key to terminal", ex);
        }
    }
    
    public void connect() {
        try {
            ttyConnector = new SshTtyConnector(connection, password);
            if (ttyConnector.connect()) {
                running.set(true);
                startReaderThread();
                logger.info("Terminal session started for {}", connection.getDisplayName());
            } else {
                showError("Verbindung fehlgeschlagen");
            }
        } catch (Exception e) {
            logger.error("Failed to connect", e);
            showError("Fehler: " + e.getMessage());
        }
    }
    
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            char[] buffer = new char[8192];
            try {
                while (running.get() && ttyConnector.isConnected()) {
                    int read = ttyConnector.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        writeToTerminal(new String(buffer, 0, read));
                    } else if (read < 0) break;
                }
            } catch (IOException e) {
                if (running.get()) logger.error("Error reading from terminal", e);
            }
            running.set(false);
        }, "Lanterna-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    private void writeToTerminal(String text) {
        if (terminalPanel != null) {
            SwingUtilities.invokeLater(() -> {
                for (char c : text.toCharArray()) {
                    terminalPanel.putCharacter(c);
                }
                terminalPanel.flush();
            });
        }
    }
    
    public void showError(String message) {
        Platform.runLater(() -> writeToTerminal("\r\n*** " + message + " ***\r\n"));
    }
    
    public void cleanup() {
        running.set(false);
        if (readerThread != null) readerThread.interrupt();
        if (ttyConnector != null) ttyConnector.close();
        if (terminalPanel != null) {
            SwingUtilities.invokeLater(() -> terminalPanel.close());
        }
    }
    
    public void requestFocus() {
        if (terminalPanel != null) {
            SwingUtilities.invokeLater(() -> terminalPanel.requestFocusInWindow());
        }
    }
    
    public int getCurrentFontSize() { return currentFontSize; }
    
    public void zoom(int delta) {
        int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, currentFontSize + delta));
        if (newSize != currentFontSize) {
            currentFontSize = newSize;
            applyZoom();
            logger.debug("Zoom changed to font size: {}", currentFontSize);
        }
    }
    
    public void resetZoom() {
        if (currentFontSize != defaultFontSize) {
            currentFontSize = defaultFontSize;
            applyZoom();
            logger.debug("Zoom reset to default font size: {}", currentFontSize);
        }
    }
    
    private void applyZoom() {
        SwingUtilities.invokeLater(() -> {
            if (terminalPanel != null) {
                // Create new terminal with new font size
                ScrollingSwingTerminal newPanel = createTerminalPanel();
                swingNode.setContent(newPanel);
                newPanel.addKeyListener(new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) { handleKeyPress(e); }
                });
                terminalPanel = newPanel;
                terminalPanel.requestFocusInWindow();
            }
        });
    }
    
    public String getTerminalHistory() { return ""; }
    public ServerConnection getConnection() { return connection; }
    public SshTtyConnector getTtyConnector() { return ttyConnector; }
    public void copyToClipboard() { }
    
    public void pasteFromClipboard() {
        if (ttyConnector != null && ttyConnector.isConnected()) {
            try {
                String clipboard = (String) Toolkit.getDefaultToolkit()
                        .getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (clipboard != null) ttyConnector.write(clipboard);
            } catch (Exception e) {
                logger.error("Error pasting from clipboard", e);
            }
        }
    }
    
    public boolean isConnected() {
        return ttyConnector != null && ttyConnector.isConnected();
    }
}
