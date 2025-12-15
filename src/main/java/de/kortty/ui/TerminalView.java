package de.kortty.ui;

import de.kortty.core.SSHSession;
import de.kortty.model.ConnectionSettings;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal view component that displays SSH output and handles input.
 */
public class TerminalView extends StackPane {
    
    private static final Logger logger = LoggerFactory.getLogger(TerminalView.class);
    
    private final SSHSession session;
    private final ConnectionSettings settings;
    
    private final ScrollPane scrollPane;
    private final TextFlow textFlow;
    private final StringBuilder terminalBuffer;
    
    private int fontSize;
    private String fontFamily;
    private Color foregroundColor;
    private Color backgroundColor;
    
    // Current text attributes for ANSI parsing
    private Color currentFgColor;
    private Color currentBgColor;
    private boolean bold = false;
    private boolean underline = false;
    
    // Selection
    private int selectionStart = -1;
    private int selectionEnd = -1;
    
    // Cursor
    private final Text cursorText;
    private final Timeline cursorBlink;
    private boolean cursorVisible = true;
    
    // ANSI escape sequence pattern (CSI sequences)
    private static final Pattern ANSI_CSI_PATTERN = Pattern.compile("\u001B\\[([0-9;?]*)([A-Za-z@`])");
    
    // OSC (Operating System Command) sequences - used for window titles etc.
    // Format: ESC ] ... BEL  or  ESC ] ... ESC \
    private static final Pattern ANSI_OSC_PATTERN = Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");
    
    // Other escape sequences to filter
    private static final Pattern ANSI_OTHER_PATTERN = Pattern.compile("\u001B[()][AB012]|\u001B[>=]|\u001B[78]");
    
    public TerminalView(SSHSession session, ConnectionSettings settings) {
        this.session = session;
        this.settings = settings;
        this.terminalBuffer = new StringBuilder();
        
        // Initialize colors and fonts from settings
        this.fontSize = settings.getFontSize();
        this.fontFamily = settings.getFontFamily();
        this.foregroundColor = Color.web(settings.getForegroundColor());
        this.backgroundColor = Color.web(settings.getBackgroundColor());
        this.currentFgColor = foregroundColor;
        this.currentBgColor = backgroundColor;
        
        // Create text flow for terminal output
        textFlow = new TextFlow();
        textFlow.setPadding(new Insets(5));
        textFlow.setLineSpacing(2);
        
        // Scroll pane
        scrollPane = new ScrollPane(textFlow);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        
        // Apply background color
        setBackground(new Background(new BackgroundFill(backgroundColor, CornerRadii.EMPTY, Insets.EMPTY)));
        scrollPane.setStyle("-fx-background: " + toHex(backgroundColor) + "; -fx-background-color: " + toHex(backgroundColor) + ";");
        textFlow.setBackground(new Background(new BackgroundFill(backgroundColor, CornerRadii.EMPTY, Insets.EMPTY)));
        
        getChildren().add(scrollPane);
        
        // Create cursor
        cursorText = new Text("█");
        cursorText.setFill(Color.web(settings.getCursorColor()));
        cursorText.setFont(Font.font(fontFamily, fontSize));
        textFlow.getChildren().add(cursorText);
        
        // Cursor blink animation
        cursorBlink = new Timeline(
            new KeyFrame(Duration.millis(500), e -> {
                cursorVisible = !cursorVisible;
                cursorText.setVisible(cursorVisible);
            })
        );
        cursorBlink.setCycleCount(Timeline.INDEFINITE);
        cursorBlink.play();
        
        // Handle keyboard input
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);
        
        // Handle mouse events for selection
        textFlow.setOnMousePressed(e -> {
            requestFocus();
            if (e.getButton() == MouseButton.PRIMARY) {
                // Start selection
                selectionStart = getCharacterIndexAt(e.getX(), e.getY());
                selectionEnd = selectionStart;
            }
        });
        
        textFlow.setOnMouseDragged(e -> {
            if (selectionStart >= 0) {
                selectionEnd = getCharacterIndexAt(e.getX(), e.getY());
                updateSelection();
            }
        });
        
        // Note: Output consumer is set by TerminalTab to coordinate between
        // terminal display and tab title updates
    }
    
    /**
     * Called externally to append output to the terminal.
     * This is called by the output consumer set in TerminalTab.
     */
    public void handleOutput(String text) {
        appendOutput(text);
    }
    
    /**
     * Called when the SSH connection is established.
     */
    public void onConnected() {
        Platform.runLater(() -> {
            requestFocus();
        });
    }
    
    /**
     * Shows an error message in the terminal.
     */
    public void showError(String error) {
        Platform.runLater(() -> {
            Text errorText = new Text("\n" + error + "\n");
            errorText.setFill(Color.RED);
            errorText.setFont(Font.font(fontFamily, fontSize));
            textFlow.getChildren().remove(cursorText);
            textFlow.getChildren().add(errorText);
            textFlow.getChildren().add(cursorText);
        });
    }
    
    /**
     * Appends output to the terminal.
     */
    private void appendOutput(String text) {
        Platform.runLater(() -> {
            terminalBuffer.append(text);
            parseAndDisplay(text);
            scrollToBottom();
        });
    }
    
    /**
     * Parses ANSI escape sequences and displays text.
     */
    private void parseAndDisplay(String text) {
        // First, remove OSC sequences (window title, etc.)
        text = ANSI_OSC_PATTERN.matcher(text).replaceAll("");
        
        // Remove other escape sequences
        text = ANSI_OTHER_PATTERN.matcher(text).replaceAll("");
        
        // Now process CSI sequences
        Matcher matcher = ANSI_CSI_PATTERN.matcher(text);
        int lastEnd = 0;
        
        while (matcher.find()) {
            // Add text before the escape sequence
            if (matcher.start() > lastEnd) {
                addText(text.substring(lastEnd, matcher.start()));
            }
            
            // Process the escape sequence
            String params = matcher.group(1);
            char command = matcher.group(2).charAt(0);
            processAnsiSequence(params, command);
            
            lastEnd = matcher.end();
        }
        
        // Add remaining text
        if (lastEnd < text.length()) {
            addText(text.substring(lastEnd));
        }
    }
    
    /**
     * Processes an ANSI escape sequence.
     */
    private void processAnsiSequence(String params, char command) {
        switch (command) {
            case 'm': // SGR (Select Graphic Rendition)
                processSGR(params);
                break;
            case 'H': // Cursor position (ignored for now)
            case 'J': // Clear screen (ignored for now)
            case 'K': // Clear line (ignored for now)
            case 'A': // Cursor up
            case 'B': // Cursor down
            case 'C': // Cursor forward
            case 'D': // Cursor back
                // TODO: Implement cursor movement
                break;
            default:
                // Unknown sequence, ignore
                break;
        }
    }
    
    /**
     * Processes SGR (Select Graphic Rendition) parameters.
     */
    private void processSGR(String params) {
        if (params.isEmpty()) {
            resetAttributes();
            return;
        }
        
        String[] codes = params.split(";");
        for (int i = 0; i < codes.length; i++) {
            try {
                int code = codes[i].isEmpty() ? 0 : Integer.parseInt(codes[i]);
                
                switch (code) {
                    case 0: // Reset
                        resetAttributes();
                        break;
                    case 1: // Bold
                        bold = true;
                        break;
                    case 4: // Underline
                        underline = true;
                        break;
                    case 22: // Normal intensity
                        bold = false;
                        break;
                    case 24: // No underline
                        underline = false;
                        break;
                    case 30: case 31: case 32: case 33:
                    case 34: case 35: case 36: case 37:
                        // Standard foreground colors
                        currentFgColor = Color.web(settings.getAnsiColor(code - 30, bold && settings.isBoldAsBright()));
                        break;
                    case 38: // Extended foreground color
                        if (i + 2 < codes.length && "5".equals(codes[i + 1])) {
                            currentFgColor = get256Color(Integer.parseInt(codes[i + 2]));
                            i += 2;
                        }
                        break;
                    case 39: // Default foreground
                        currentFgColor = foregroundColor;
                        break;
                    case 40: case 41: case 42: case 43:
                    case 44: case 45: case 46: case 47:
                        // Standard background colors
                        currentBgColor = Color.web(settings.getAnsiColor(code - 40, false));
                        break;
                    case 48: // Extended background color
                        if (i + 2 < codes.length && "5".equals(codes[i + 1])) {
                            currentBgColor = get256Color(Integer.parseInt(codes[i + 2]));
                            i += 2;
                        }
                        break;
                    case 49: // Default background
                        currentBgColor = backgroundColor;
                        break;
                    case 90: case 91: case 92: case 93:
                    case 94: case 95: case 96: case 97:
                        // Bright foreground colors
                        currentFgColor = Color.web(settings.getAnsiColor(code - 90, true));
                        break;
                    case 100: case 101: case 102: case 103:
                    case 104: case 105: case 106: case 107:
                        // Bright background colors
                        currentBgColor = Color.web(settings.getAnsiColor(code - 100, true));
                        break;
                }
            } catch (NumberFormatException e) {
                // Ignore invalid codes
            }
        }
    }
    
    /**
     * Resets text attributes to defaults.
     */
    private void resetAttributes() {
        currentFgColor = foregroundColor;
        currentBgColor = backgroundColor;
        bold = false;
        underline = false;
    }
    
    /**
     * Gets a color from the 256-color palette.
     */
    private Color get256Color(int index) {
        if (index < 16) {
            // Standard colors
            return Color.web(settings.getAnsiColor(index % 8, index >= 8));
        } else if (index < 232) {
            // 216 color cube (6x6x6)
            index -= 16;
            int r = (index / 36) * 51;
            int g = ((index / 6) % 6) * 51;
            int b = (index % 6) * 51;
            return Color.rgb(r, g, b);
        } else {
            // Grayscale
            int gray = (index - 232) * 10 + 8;
            return Color.rgb(gray, gray, gray);
        }
    }
    
    /**
     * Adds text with current attributes.
     */
    private void addText(String text) {
        // Filter out control characters except newlines and tabs
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        if (text.isEmpty()) {
            return;
        }

        Text textNode = new Text(text);
        textNode.setFill(currentFgColor);
        textNode.setFont(Font.font(fontFamily, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, fontSize));

        if (underline) {
            textNode.setUnderline(true);
        }

        // Remove cursor, add text, then add cursor back at the end
        textFlow.getChildren().remove(cursorText);
        textFlow.getChildren().add(textNode);
        textFlow.getChildren().add(cursorText);
        
        // Reset cursor blink to visible when new text arrives
        cursorVisible = true;
        cursorText.setVisible(true);
    }
    
    /**
     * Handles key press events.
     */
    private void handleKeyPressed(KeyEvent event) {
        if (!session.isConnected()) {
            return;
        }
        
        try {
            KeyCode code = event.getCode();
            
            // Handle special keys
            SSHSession.SpecialKey specialKey = switch (code) {
                case ENTER -> SSHSession.SpecialKey.ENTER;
                case TAB -> SSHSession.SpecialKey.TAB;
                case BACK_SPACE -> SSHSession.SpecialKey.BACKSPACE;
                case ESCAPE -> SSHSession.SpecialKey.ESCAPE;
                case UP -> SSHSession.SpecialKey.UP;
                case DOWN -> SSHSession.SpecialKey.DOWN;
                case LEFT -> SSHSession.SpecialKey.LEFT;
                case RIGHT -> SSHSession.SpecialKey.RIGHT;
                case HOME -> SSHSession.SpecialKey.HOME;
                case END -> SSHSession.SpecialKey.END;
                case PAGE_UP -> SSHSession.SpecialKey.PAGE_UP;
                case PAGE_DOWN -> SSHSession.SpecialKey.PAGE_DOWN;
                case INSERT -> SSHSession.SpecialKey.INSERT;
                case DELETE -> SSHSession.SpecialKey.DELETE;
                case F1 -> SSHSession.SpecialKey.F1;
                case F2 -> SSHSession.SpecialKey.F2;
                case F3 -> SSHSession.SpecialKey.F3;
                case F4 -> SSHSession.SpecialKey.F4;
                case F5 -> SSHSession.SpecialKey.F5;
                case F6 -> SSHSession.SpecialKey.F6;
                case F7 -> SSHSession.SpecialKey.F7;
                case F8 -> SSHSession.SpecialKey.F8;
                case F9 -> SSHSession.SpecialKey.F9;
                case F10 -> SSHSession.SpecialKey.F10;
                case F11 -> SSHSession.SpecialKey.F11;
                case F12 -> SSHSession.SpecialKey.F12;
                default -> null;
            };
            
            if (specialKey != null) {
                session.sendSpecialKey(specialKey);
                event.consume();
            } else if (event.isControlDown() && !event.isAltDown()) {
                // Handle Ctrl+key combinations
                String text = event.getText();
                if (text.length() == 1) {
                    char c = text.charAt(0);
                    if (c >= 'a' && c <= 'z') {
                        session.sendChar((char) (c - 'a' + 1));
                        event.consume();
                    } else if (c >= 'A' && c <= 'Z') {
                        session.sendChar((char) (c - 'A' + 1));
                        event.consume();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to send key", e);
        }
    }
    
    /**
     * Handles key typed events.
     */
    private void handleKeyTyped(KeyEvent event) {
        if (!session.isConnected()) {
            return;
        }
        
        String character = event.getCharacter();
        if (character.length() == 1 && !event.isControlDown() && !event.isMetaDown()) {
            char c = character.charAt(0);
            if (c >= 32 || c == '\r' || c == '\n' || c == '\t') {
                try {
                    session.sendChar(c);
                } catch (IOException e) {
                    logger.error("Failed to send character", e);
                }
            }
        }
    }
    
    /**
     * Sends text to the terminal.
     */
    public void sendText(String text) {
        if (session.isConnected()) {
            try {
                session.sendInput(text);
            } catch (IOException e) {
                logger.error("Failed to send text", e);
            }
        }
    }
    
    /**
     * Gets selected text.
     */
    public String getSelectedText() {
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) {
            return null;
        }
        
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        
        if (start >= 0 && end <= terminalBuffer.length()) {
            return terminalBuffer.substring(start, end);
        }
        return null;
    }
    
    private int getCharacterIndexAt(double x, double y) {
        // Simplified character index calculation
        // In a real implementation, this would need to consider font metrics
        return (int) (y / (fontSize + 2) * 80 + x / (fontSize * 0.6));
    }
    
    private void updateSelection() {
        // TODO: Implement visual selection highlighting
    }
    
    /**
     * Scrolls to the bottom of the terminal.
     */
    private void scrollToBottom() {
        scrollPane.setVvalue(1.0);
    }
    
    /**
     * Zooms the terminal font.
     */
    public void zoom(int delta) {
        fontSize = Math.max(8, Math.min(72, fontSize + delta));
        updateFont();
    }
    
    /**
     * Resets the terminal font size.
     */
    public void resetZoom() {
        fontSize = settings.getFontSize();
        updateFont();
    }
    
    private void updateFont() {
        for (var node : textFlow.getChildren()) {
            if (node instanceof Text text) {
                if (text == cursorText) {
                    text.setFont(Font.font(fontFamily, fontSize));
                } else {
                    text.setFont(Font.font(fontFamily, text.getFont().getStyle().contains("Bold") ? 
                            javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, fontSize));
                }
            }
        }
    }
    
    /**
     * Stops the cursor animation and cleans up resources.
     */
    public void cleanup() {
        if (cursorBlink != null) {
            cursorBlink.stop();
        }
    }
    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
}
