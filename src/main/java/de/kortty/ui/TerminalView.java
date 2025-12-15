package de.kortty.ui;

import de.kortty.core.SSHSession;
import de.kortty.model.ConnectionSettings;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.*;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
    private double selectionStartX, selectionStartY;
    private final List<Text> selectedTextNodes = new ArrayList<>();
    private String selectedText = null;
    
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
                // Clear previous selection
                clearSelection();
                // Start new selection
                selectionStartX = e.getX();
                selectionStartY = e.getY();
                selectionStart = getCharacterIndexAt(e.getX(), e.getY());
                selectionEnd = selectionStart;
            }
        });

        textFlow.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && selectionStart >= 0) {
                selectionEnd = getCharacterIndexAt(e.getX(), e.getY());
                updateSelection(selectionStartX, selectionStartY, e.getX(), e.getY());
            }
        });
        
        textFlow.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                // Finalize selection
                buildSelectedText();
            }
        });
        
        // Context menu for copy/paste
        ContextMenu contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("Kopieren");
        copyItem.setOnAction(e -> copyToClipboard());
        MenuItem pasteItem = new MenuItem("Einfügen");
        pasteItem.setOnAction(e -> pasteFromClipboard());
        MenuItem selectAllItem = new MenuItem("Alles auswählen");
        selectAllItem.setOnAction(e -> selectAll());
        contextMenu.getItems().addAll(copyItem, pasteItem, new SeparatorMenuItem(), selectAllItem);
        textFlow.setOnContextMenuRequested(e -> {
            copyItem.setDisable(selectedText == null || selectedText.isEmpty());
            contextMenu.show(textFlow, e.getScreenX(), e.getScreenY());
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
        
        // Store original color for selection restoration
        textNode.setUserData(currentFgColor);

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
        KeyCode code = event.getCode();
        
        // Handle Copy/Paste shortcuts (work even when disconnected)
        if (event.isShortcutDown()) {
            if (code == KeyCode.C) {
                // Ctrl+C / Cmd+C: Copy if text is selected, otherwise send SIGINT
                if (selectedText != null && !selectedText.isEmpty()) {
                    copyToClipboard();
                    event.consume();
                    return;
                }
                // If nothing selected, send Ctrl+C to terminal (SIGINT)
            } else if (code == KeyCode.V) {
                // Ctrl+V / Cmd+V: Paste
                pasteFromClipboard();
                event.consume();
                return;
            } else if (code == KeyCode.A) {
                // Ctrl+A / Cmd+A: Select all
                selectAll();
                event.consume();
                return;
            }
        }
        
        if (!session.isConnected()) {
            return;
        }
        
        // Clear selection when typing
        if (!event.isShortcutDown() && !event.isAltDown()) {
            clearSelection();
        }
        
        try {
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
            } else if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                // Handle Ctrl+key combinations (send control characters to terminal)
                if (code.isLetterKey()) {
                    char c = code.getChar().charAt(0);
                    if (c >= 'A' && c <= 'Z') {
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
        return selectedText;
    }
    
    /**
     * Clears the current selection.
     */
    private void clearSelection() {
        // Reset highlighting on previously selected nodes - restore original colors
        for (Text node : selectedTextNodes) {
            // Restore original color from userData
            if (node.getUserData() instanceof Color originalColor) {
                node.setFill(originalColor);
            } else {
                node.setFill(foregroundColor);
            }
        }
        selectedTextNodes.clear();
        selectedText = null;
        selectionStart = -1;
        selectionEnd = -1;
    }
    
    /**
     * Updates the visual selection based on mouse coordinates.
     */
    private void updateSelection(double startX, double startY, double endX, double endY) {
        // Clear previous highlighting - restore original colors
        for (Text node : selectedTextNodes) {
            if (node != cursorText) {
                if (node.getUserData() instanceof Color originalColor) {
                    node.setFill(originalColor);
                } else {
                    node.setFill(foregroundColor);
                }
            }
        }
        selectedTextNodes.clear();
        
        // Calculate selection bounds
        double minY = Math.min(startY, endY);
        double maxY = Math.max(startY, endY);
        double minX = startY < endY ? startX : (startY > endY ? endX : Math.min(startX, endX));
        double maxX = startY < endY ? endX : (startY > endY ? startX : Math.max(startX, endX));
        
        // Highlight text nodes within selection
        for (var node : textFlow.getChildren()) {
            if (node instanceof Text textNode && textNode != cursorText) {
                double nodeY = textNode.getLayoutY();
                double nodeX = textNode.getLayoutX();
                double nodeWidth = textNode.getBoundsInLocal().getWidth();
                double nodeHeight = textNode.getBoundsInLocal().getHeight();
                
                // Check if node is within selection area
                boolean inSelection = false;
                if (nodeY + nodeHeight >= minY && nodeY <= maxY) {
                    if (nodeY > minY && nodeY + nodeHeight < maxY) {
                        // Fully within vertical range
                        inSelection = true;
                    } else if (Math.abs(maxY - minY) < nodeHeight * 1.5) {
                        // Single line selection
                        if (nodeX + nodeWidth >= minX && nodeX <= maxX) {
                            inSelection = true;
                        }
                    } else {
                        // Multi-line - check edges
                        if (nodeY <= minY + nodeHeight && nodeX + nodeWidth >= minX) {
                            inSelection = true;
                        } else if (nodeY + nodeHeight >= maxY - nodeHeight && nodeX <= maxX) {
                            inSelection = true;
                        } else if (nodeY > minY + nodeHeight && nodeY + nodeHeight < maxY - nodeHeight) {
                            inSelection = true;
                        }
                    }
                }
                
                if (inSelection) {
                    textNode.setFill(Color.web(settings.getSelectionColor()));
                    selectedTextNodes.add(textNode);
                }
            }
        }
    }
    
    /**
     * Builds the selected text string from selected nodes.
     */
    private void buildSelectedText() {
        StringBuilder sb = new StringBuilder();
        for (var node : textFlow.getChildren()) {
            if (node instanceof Text textNode && selectedTextNodes.contains(textNode)) {
                sb.append(textNode.getText());
            }
        }
        selectedText = sb.length() > 0 ? sb.toString() : null;
    }
    
    /**
     * Copies selected text to clipboard.
     */
    public void copyToClipboard() {
        if (selectedText != null && !selectedText.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(selectedText);
            Clipboard.getSystemClipboard().setContent(content);
            logger.debug("Copied {} characters to clipboard", selectedText.length());
        }
    }
    
    /**
     * Pastes text from clipboard to terminal.
     */
    public void pasteFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            sendText(text);
        }
    }
    
    /**
     * Selects all text in the terminal.
     */
    public void selectAll() {
        // First clear previous selection (restores colors)
        clearSelection();
        // Then select all
        for (var node : textFlow.getChildren()) {
            if (node instanceof Text textNode && textNode != cursorText) {
                // Store original color before changing to selection color
                if (textNode.getUserData() == null) {
                    textNode.setUserData(textNode.getFill());
                }
                textNode.setFill(Color.web(settings.getSelectionColor()));
                selectedTextNodes.add(textNode);
            }
        }
        buildSelectedText();
    }

    private int getCharacterIndexAt(double x, double y) {
        // Calculate approximate character position
        int line = (int) (y / (fontSize * 1.5));
        int col = (int) (x / (fontSize * 0.6));
        return line * 80 + col; // Approximate - used for tracking selection start/end
    }
    
    /**
     * Scrolls to the bottom of the terminal.
     */
    private void scrollToBottom() {
        // Use layout pass to ensure content is updated before scrolling
        textFlow.layout();
        scrollPane.layout();
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
