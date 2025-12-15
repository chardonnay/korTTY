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
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
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
    
    // Selection highlight rectangles
    private final Pane selectionPane;
    private final List<Rectangle> selectionRectangles = new ArrayList<>();
    
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
        
        // Create selection pane (below text for highlighting)
        selectionPane = new Pane();
        selectionPane.setMouseTransparent(true);
        
        // Create text flow for terminal output
        textFlow = new TextFlow();
        textFlow.setPadding(new Insets(5));
        textFlow.setLineSpacing(2);
        
        // Stack selection pane under text flow
        StackPane textContainer = new StackPane();
        textContainer.getChildren().addAll(selectionPane, textFlow);
        
        // Scroll pane
        scrollPane = new ScrollPane(textContainer);
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
        
        // Handle carriage return: \r followed by text (not \n) means overwrite current line
        // For simplicity, we convert \r (not followed by \n) to nothing (the server is repositioning cursor)
        // This prevents duplicate prompts when server sends "\r" to redraw the line
        text = text.replaceAll("\r(?!\n)", "");
        
        // Also remove any clear line sequences that might cause issues
        text = text.replaceAll("\u001B\\[K", "");
        text = text.replaceAll("\u001B\\[0K", "");
        text = text.replaceAll("\u001B\\[1K", "");
        text = text.replaceAll("\u001B\\[2K", "");
        
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

        if (text.isEmpty()) {
            return;
        }
        
        // Check for excessive newlines at buffer end combined with new text
        String bufferEnd = terminalBuffer.length() > 0 ? 
            terminalBuffer.substring(Math.max(0, terminalBuffer.length() - 2)) : "";
        
        // If buffer ends with newlines and text starts with newlines, limit to max 1 newline
        if (bufferEnd.endsWith("\n") && text.startsWith("\n")) {
            // Remove leading newlines from text, keep just one
            text = text.replaceFirst("^\n+", "\n");
            // If buffer already has double newline, skip this one too
            if (bufferEnd.equals("\n\n")) {
                text = text.replaceFirst("^\n", "");
            }
        }
        
        if (text.isEmpty()) {
            return;
        }

        // Record the start index BEFORE adding to buffer
        int startIndex = terminalBuffer.length();
        
        // Add cleaned text to buffer
        terminalBuffer.append(text);

        Text textNode = new Text(text);
        textNode.setFill(currentFgColor);
        textNode.setFont(Font.font(fontFamily, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, fontSize));
        
        // Store original color for selection restoration
        textNode.setUserData(currentFgColor);

        if (underline) {
            textNode.setUnderline(true);
        }

        // Track text node for character-based selection (with correct index)
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
        
        // On Mac, use Cmd for GUI shortcuts (copy/paste/select all)
        // On other platforms, use Ctrl but only for copy/paste when text is selected
        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
        boolean isGuiShortcut = isMac ? event.isMetaDown() : event.isShortcutDown();
        
        // Handle GUI shortcuts (Cmd+C/V/A on Mac, Ctrl+C/V/A on others only when appropriate)
        if (isGuiShortcut) {
            if (code == KeyCode.C && selectedText != null && !selectedText.isEmpty()) {
                // Copy selected text
                copyToClipboard();
                event.consume();
                return;
            } else if (code == KeyCode.V) {
                // Paste
                pasteFromClipboard();
                event.consume();
                return;
            } else if (code == KeyCode.A && isMac) {
                // Select all (only on Mac with Cmd, Ctrl+A goes to terminal on all platforms)
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
        
        // Clear selection when typing (but not for modifier keys alone)
        if (!event.isShortcutDown() && !event.isAltDown() && !event.isControlDown() && !event.isMetaDown()) {
            clearSelection();
        }
        
        try {
            // Handle special keys first
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
                // Handle Ctrl+key combinations - send control characters to terminal
                // Ctrl+A = 1 (SOH, start of line), Ctrl+B = 2, ..., Ctrl+Z = 26
                // Ctrl+E = 5 (end of line), Ctrl+K = 11 (kill to end), etc.
                if (code.isLetterKey()) {
                    String keyChar = code.getChar();
                    if (keyChar != null && !keyChar.isEmpty()) {
                        char c = keyChar.toUpperCase().charAt(0);
                        if (c >= 'A' && c <= 'Z') {
                            int controlChar = c - 'A' + 1;
                            logger.debug("Sending Ctrl+{} as control character {}", c, controlChar);
                            session.sendChar((char) controlChar);
                            event.consume();
                        }
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
        // Clear selection rectangles
        selectionPane.getChildren().clear();
        selectionRectangles.clear();
        
        selectedText = null;
        selectionStartIndex = -1;
        selectionEndIndex = -1;
    }
    
    /**
     * Gets the character index at a given screen position using actual text node positions.
     */
    private int getCharacterIndexAtPosition(double x, double y) {
        String buffer = terminalBuffer.toString();
        if (buffer.isEmpty()) return 0;
        
        // Try to find the exact text node at this position
        for (TextNodeInfo info : textNodeInfos) {
            Text node = info.node;
            double nodeX = node.getLayoutX();
            double nodeY = node.getLayoutY();
            double nodeWidth = node.getBoundsInLocal().getWidth();
            double nodeHeight = node.getBoundsInLocal().getHeight();
            
            // Check if click is within this node's bounds
            if (x >= nodeX && x <= nodeX + nodeWidth && 
                y >= nodeY && y <= nodeY + nodeHeight) {
                
                // Calculate character position within node
                String nodeText = node.getText();
                if (nodeText.isEmpty()) continue;
                
                // Estimate character width
                double charWidth = nodeWidth / nodeText.length();
                int charPos = (int) ((x - nodeX) / charWidth);
                charPos = Math.max(0, Math.min(charPos, nodeText.length()));
                
                return info.startIndex + charPos;
            }
        }
        
        // Fallback: use line-based calculation
        double lineHeight = fontSize * 1.4;
        double charWidth = fontSize * 0.6;
        
        double adjustedY = y - 5;
        double adjustedX = x - 5;
        
        int lineNum = (int) Math.max(0, adjustedY / lineHeight);
        
        String[] lines = buffer.split("\n", -1);
        int charIndex = 0;
        
        for (int i = 0; i < lines.length && i < lineNum; i++) {
            charIndex += lines[i].length() + 1;
        }
        
        if (lineNum < lines.length) {
            int colPos = (int) Math.max(0, adjustedX / charWidth);
            colPos = Math.min(colPos, lines[lineNum].length());
            charIndex += colPos;
        }
        
        return Math.min(charIndex, buffer.length());
    }
    
    /**
     * Updates the selection highlighting based on character indices.
     * Uses rectangles behind text for proper selection appearance.
     */
    private void updateSelectionHighlight() {
        // Clear existing selection rectangles
        selectionPane.getChildren().clear();
        selectionRectangles.clear();
        
        int start = Math.min(selectionStartIndex, selectionEndIndex);
        int end = Math.max(selectionStartIndex, selectionEndIndex);
        
        if (start < 0 || end <= start) {
            return;
        }
        
        Color selectionBgColor = Color.web(settings.getSelectionColor()).deriveColor(0, 1, 1, 0.7);
        
        // Create rectangles for each selected text node
        for (TextNodeInfo info : textNodeInfos) {
            // Check if this node overlaps with selection
            if (info.endIndex > start && info.startIndex < end) {
                Text node = info.node;
                
                // Get node bounds
                double nodeX = node.getLayoutX() + textFlow.getPadding().getLeft();
                double nodeY = node.getLayoutY() + textFlow.getPadding().getTop();
                double nodeWidth = node.getBoundsInLocal().getWidth();
                double nodeHeight = node.getBoundsInLocal().getHeight();
                
                // Calculate partial selection within the node
                int nodeStart = info.startIndex;
                int nodeEnd = info.endIndex;
                String nodeText = node.getText();
                
                double charWidth = nodeWidth / Math.max(1, nodeText.length());
                
                // Calculate the selected portion
                int selStartInNode = Math.max(0, start - nodeStart);
                int selEndInNode = Math.min(nodeText.length(), end - nodeStart);
                
                if (selStartInNode < selEndInNode) {
                    double rectX = nodeX + (selStartInNode * charWidth);
                    double rectWidth = (selEndInNode - selStartInNode) * charWidth;
                    
                    Rectangle rect = new Rectangle(rectX, nodeY - fontSize * 0.2, rectWidth, nodeHeight + fontSize * 0.1);
                    rect.setFill(selectionBgColor);
                    rect.setMouseTransparent(true);
                    selectionRectangles.add(rect);
                    selectionPane.getChildren().add(rect);
                }
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
