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
    
    // Selection - character-based for precise selection
    private int selectionStartIndex = -1;
    private int selectionEndIndex = -1;
    private String selectedText = null;
    
    // Text tracking for precise character selection
    private final List<TextNodeInfo> textNodeInfos = new ArrayList<>();
    
    /**
     * Helper class to track text node positions and content.
     */
    private static class TextNodeInfo {
        final Text node;
        final int startIndex;
        final int endIndex;
        final Color originalColor;
        
        TextNodeInfo(Text node, int startIndex, Color originalColor) {
            this.node = node;
            this.startIndex = startIndex;
            this.endIndex = startIndex + node.getText().length();
            this.originalColor = originalColor;
        }
    }
    
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
                // Start new selection at character position
                selectionStartIndex = getCharacterIndexAtPosition(e.getX(), e.getY());
                selectionEndIndex = selectionStartIndex;
                
                // Double-click to select word
                if (e.getClickCount() == 2) {
                    selectWordAt(selectionStartIndex);
                    e.consume();
                }
                // Triple-click to select line
                else if (e.getClickCount() == 3) {
                    selectLineAt(selectionStartIndex);
                    e.consume();
                }
            }
        });

        textFlow.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && selectionStartIndex >= 0) {
                selectionEndIndex = getCharacterIndexAtPosition(e.getX(), e.getY());
                updateSelectionHighlight();
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
        
        // Normalize line endings: \r\n -> \n, lone \r -> \n
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        
        // Remove excessive blank lines (more than 2 consecutive newlines -> 2)
        text = text.replaceAll("\n{3,}", "\n\n");

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

        // Track text node for character-based selection
        int startIndex = terminalBuffer.length() - text.length();
        if (startIndex < 0) startIndex = 0;
        textNodeInfos.add(new TextNodeInfo(textNode, startIndex, currentFgColor));

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
        
        // Auto-scroll to bottom when user presses a key
        scrollToBottom();
        
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
        
        // Auto-scroll to bottom when user types
        scrollToBottom();
        
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
        // Reset highlighting on all text nodes - restore original colors
        for (TextNodeInfo info : textNodeInfos) {
            info.node.setFill(info.originalColor);
        }
        selectedText = null;
        selectionStartIndex = -1;
        selectionEndIndex = -1;
    }
    
    /**
     * Gets the character index at a given screen position.
     */
    private int getCharacterIndexAtPosition(double x, double y) {
        String buffer = terminalBuffer.toString();
        if (buffer.isEmpty()) return 0;
        
        // Calculate approximate line height and character width based on font metrics
        double lineHeight = fontSize * 1.4;
        double charWidth = fontSize * 0.6;
        
        // Account for padding
        double adjustedY = y - 5;
        double adjustedX = x - 5;
        
        // Find the line number
        int lineNum = (int) Math.max(0, adjustedY / lineHeight);
        
        // Split buffer into lines and find the correct position
        String[] lines = buffer.split("\n", -1);
        int charIndex = 0;
        
        for (int i = 0; i < lines.length && i < lineNum; i++) {
            charIndex += lines[i].length() + 1; // +1 for newline
        }
        
        // Add column position within the line
        if (lineNum < lines.length) {
            int colPos = (int) Math.max(0, adjustedX / charWidth);
            colPos = Math.min(colPos, lines[lineNum].length());
            charIndex += colPos;
        }
        
        return Math.min(charIndex, buffer.length());
    }
    
    /**
     * Updates the selection highlighting based on character indices.
     */
    private void updateSelectionHighlight() {
        int start = Math.min(selectionStartIndex, selectionEndIndex);
        int end = Math.max(selectionStartIndex, selectionEndIndex);
        
        String buffer = terminalBuffer.toString();
        Color selectionColor = Color.web(settings.getSelectionColor());
        
        for (TextNodeInfo info : textNodeInfos) {
            // Check if this node overlaps with selection
            if (info.endIndex > start && info.startIndex < end) {
                // Node is at least partially selected
                info.node.setFill(selectionColor);
            } else {
                // Node is not selected - restore original color
                info.node.setFill(info.originalColor);
            }
        }
    }
    
    /**
     * Selects the word at the given character index.
     */
    private void selectWordAt(int index) {
        String buffer = terminalBuffer.toString();
        if (buffer.isEmpty() || index < 0 || index >= buffer.length()) return;
        
        // Find word boundaries
        int start = index;
        int end = index;
        
        // Expand backwards to find word start
        while (start > 0 && isWordChar(buffer.charAt(start - 1))) {
            start--;
        }
        
        // Expand forwards to find word end
        while (end < buffer.length() && isWordChar(buffer.charAt(end))) {
            end++;
        }
        
        if (start < end) {
            selectionStartIndex = start;
            selectionEndIndex = end;
            updateSelectionHighlight();
            buildSelectedText();
        }
    }
    
    /**
     * Selects the line at the given character index.
     */
    private void selectLineAt(int index) {
        String buffer = terminalBuffer.toString();
        if (buffer.isEmpty() || index < 0) return;
        
        // Find line boundaries
        int start = buffer.lastIndexOf('\n', index - 1) + 1;
        int end = buffer.indexOf('\n', index);
        if (end < 0) end = buffer.length();
        
        selectionStartIndex = start;
        selectionEndIndex = end;
        updateSelectionHighlight();
        buildSelectedText();
    }
    
    /**
     * Checks if a character is part of a word.
     */
    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/';
    }
    
    /**
     * Builds the selected text string from character indices.
     */
    private void buildSelectedText() {
        if (selectionStartIndex < 0 || selectionEndIndex < 0) {
            selectedText = null;
            return;
        }
        
        int start = Math.min(selectionStartIndex, selectionEndIndex);
        int end = Math.max(selectionStartIndex, selectionEndIndex);
        
        String buffer = terminalBuffer.toString();
        if (start >= 0 && end <= buffer.length() && start < end) {
            selectedText = buffer.substring(start, end);
        } else {
            selectedText = null;
        }
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
        String buffer = terminalBuffer.toString();
        if (buffer.isEmpty()) return;
        
        selectionStartIndex = 0;
        selectionEndIndex = buffer.length();
        updateSelectionHighlight();
        buildSelectedText();
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
