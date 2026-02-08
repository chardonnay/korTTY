package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.model.SessionState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tab for editing text files with syntax highlighting.
 * Supports various file formats with appropriate syntax highlighting.
 */
public class FileEditorTab extends Tab {
    
    private static final Logger logger = LoggerFactory.getLogger(FileEditorTab.class);
    
    private final InlineCssTextArea codeArea;
    private final Label statusLabel;
    private final SFTPSession sftpSession;
    private final String remotePath;
    private final boolean isRemoteFile;
    private Path localPath;
    private boolean isModified = false;
    private FileType fileType;
    
    // Search and replace
    private TextField searchField;
    private TextField replaceField;
    private CheckBox regexCheckBox;
    private CheckBox caseSensitiveCheckBox;
    private Label searchResultLabel;
    private int currentSearchIndex = -1;
    private java.util.List<Integer> searchMatches = new java.util.ArrayList<>();
    
    // Font size management
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 48;
    private static final int DEFAULT_FONT_SIZE = 14;
    private int currentFontSize = DEFAULT_FONT_SIZE;
    private Label fontSizeLabel;
    private String editorFontFamily = "Monospaced";
    private String editorForegroundColor = "#000000";
    private String editorBackgroundColor = "#FFFFFF";
    private String editorCursorStyle = "BLOCK"; // BLOCK, LINE, UNDERSCORE
    private String editorCursorColor = "#FF0000"; // red for visibility
    
    // Whitespace visualization
    private boolean showWhitespace = false;
    private ToggleButton whitespaceToggle;
    
    // Line ending management
    private enum LineEnding { LF, CRLF, CR, MIXED }
    private LineEnding detectedLineEnding = LineEnding.LF;
    private Label lineEndingLabel;
    
    // Large file handling
    private static final long LARGE_FILE_THRESHOLD_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final int CHUNK_SIZE_BYTES = 512 * 1024; // 512 KB
    private static final long HIGHLIGHT_LIMIT_BYTES = 2L * 1024 * 1024; // Highlight up to 2 MB in large mode
    private boolean largeFileMode = false;
    private Path largeFilePath;
    private long fileSizeBytes = 0;
    private long loadedBytes = 0;
    private Button loadMoreButton;
    
    /**
     * Reads a local file with encoding fallback.
     * Tries UTF-8 first, then falls back to ISO-8859-1 (which can decode any byte).
     */
    private String readFileWithFallback(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        try {
            // Try strict UTF-8 decoding first
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            logger.warn("File {} is not valid UTF-8, falling back to ISO-8859-1", path.getFileName());
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
    
    private void loadEditorSettings() {
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings gs = gsm.getSettings();
            if (gs != null) {
                // Prefer terminal default settings if present
                de.kortty.model.ConnectionSettings term = gs.getDefaultTerminalSettings();
                if (term != null) {
                    if (term.getFontFamily() != null) {
                        editorFontFamily = term.getFontFamily();
                    }
                    if (term.getForegroundColor() != null) {
                        editorForegroundColor = term.getForegroundColor();
                    }
                    if (term.getBackgroundColor() != null) {
                        editorBackgroundColor = term.getBackgroundColor();
                    }
                    currentFontSize = term.getFontSize() > 0 ? term.getFontSize() : DEFAULT_FONT_SIZE;
                }
                // Load cursor style and color from global settings
                String loadedStyle = gs.getEditorCursorStyle();
                String loadedColor = gs.getEditorCursorColor();
                
                logger.info("Loading editor cursor settings - style: '{}', color: '{}'", loadedStyle, loadedColor);
                
                if (loadedStyle != null && !loadedStyle.isEmpty()) {
                    editorCursorStyle = loadedStyle;
                }
                if (loadedColor != null && !loadedColor.isEmpty()) {
                    editorCursorColor = loadedColor;
                }
                
                logger.info("Final editor cursor settings - style: '{}', color: '{}'", editorCursorStyle, editorCursorColor);
            }
        } catch (Exception e) {
            logger.warn("Could not load editor settings, using defaults", e);
        }
    }
    
    public enum FileType {
        PLAIN_TEXT,
        XML,
        JSON,
        YAML,
        TOML,
        MARKDOWN,
        INI,
        CFG,
        JINJA2,
        ANSIBLE_YAML,
        PYTHON,
        PERL,
        RUBY,
        SHELL,
        HTML,
        CSS,
        JAVASCRIPT,
        JAVA,
        GO,
        RUST,
        SQL,
        DOCKERFILE,
        TERRAFORM,
        PUPPET,
        CFENGINE3
    }
    
    /**
     * Constructor for remote file editing.
     */
    public FileEditorTab(String filename, String remotePath, SFTPSession sftpSession, byte[] content) {
        this.remotePath = remotePath;
        this.sftpSession = sftpSession;
        this.isRemoteFile = true;
        this.fileType = detectFileType(filename);
        
        setText(filename + " (Remote)");
        setClosable(true);
        
        // Create code area (InlineCssTextArea for reliable syntax highlighting)
        codeArea = new InlineCssTextArea();
        codeArea.getStyleClass().add("code-area");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        // Font size will be applied after UI setup
        
        loadEditorSettings();
        
        // Detect line endings before normalizing
        String rawText = new String(content, StandardCharsets.UTF_8);
        detectedLineEnding = detectLineEnding(rawText);
        
        // Load content (InlineCssTextArea normalizes to \n internally)
        codeArea.replaceText(0, 0, rawText);
        codeArea.getUndoManager().forgetHistory();
        
        // Track modifications
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!isModified) {
                isModified = true;
                updateTitle();
            }
        });
        
        // Apply syntax highlighting
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        });
        
        // Initial highlighting
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        
        // Context menu for right-click
        codeArea.setContextMenu(createContextMenu());
        
        // Status bar
        statusLabel = new Label(I18n.get("editor.status.ready"));
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane rootContent = createContent();
        setContent(rootContent);
        
        // Keyboard shortcuts
        setupKeyboardShortcuts();
        
        // Apply initial font size and colors
        applyFontAndColors();
        
        logger.info("Opened remote file for editing: {}", remotePath);
    }
    
    /**
     * Constructor for local file editing.
     */
    public FileEditorTab(Path localPath) throws Exception {
        this.localPath = localPath;
        this.remotePath = null;
        this.sftpSession = null;
        this.isRemoteFile = false;
        this.fileType = detectFileType(localPath.getFileName().toString());
        
        setText(localPath.getFileName().toString());
        setClosable(true);
        
        // Create code area (InlineCssTextArea for reliable syntax highlighting)
        codeArea = new InlineCssTextArea();
        codeArea.getStyleClass().add("code-area");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        // Font size will be applied after UI setup
        
        loadEditorSettings();
        
        // Detect line endings before normalizing
        String rawText = readFileWithFallback(localPath);
        detectedLineEnding = detectLineEnding(rawText);
        
        // Load content (InlineCssTextArea normalizes to \n internally)
        codeArea.replaceText(0, 0, rawText);
        codeArea.getUndoManager().forgetHistory();
        
        // Track modifications
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!isModified) {
                isModified = true;
                updateTitle();
            }
        });
        
        // Apply syntax highlighting
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        });
        
        // Initial highlighting
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        
        // Context menu for right-click
        codeArea.setContextMenu(createContextMenu());
        
        // Status bar
        statusLabel = new Label(I18n.get("editor.status.ready"));
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane rootContent = createContent();
        setContent(rootContent);
        
        // Keyboard shortcuts
        setupKeyboardShortcuts();
        
        // Apply initial font size and colors
        applyFontAndColors();
        
        logger.info("Opened local file for editing: {}", localPath);
    }
    
    private BorderPane createContent() {
        BorderPane root = new BorderPane();
        
        // Toolbar
        ToolBar toolBar = createToolBar();
        
        // Search/Replace panel (initially hidden)
        VBox searchPanel = createSearchPanel();
        searchPanel.setVisible(false);
        searchPanel.setManaged(false);
        
        // Top area with toolbar and search
        VBox topArea = new VBox(toolBar, searchPanel);
        root.setTop(topArea);
        
        // Code editor
        root.setCenter(codeArea);
        
        // Status bar with line ending indicator
        lineEndingLabel = new Label(getLineEndingDisplayName());
        lineEndingLabel.setStyle("-fx-padding: 5px; -fx-cursor: hand; -fx-font-weight: bold;");
        lineEndingLabel.setTooltip(new Tooltip(I18n.get("editor.lineEnding.tooltip")));
        lineEndingLabel.setOnMouseClicked(e -> showLineEndingMenu());
        
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        HBox statusBar = new HBox(statusLabel, spacer, lineEndingLabel);
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        root.setBottom(statusBar);
        
        return root;
    }
    
    private ToolBar createToolBar() {
        Button saveBtn = new Button(I18n.get("editor.save"));
        saveBtn.setOnAction(e -> save());
        
        Button saveAsBtn = new Button(I18n.get("editor.saveAs"));
        saveAsBtn.setOnAction(e -> saveAs());
        
        Button closeBtn = new Button(I18n.get("editor.close"));
        closeBtn.setOnAction(e -> closeTab());
        
        Button findBtn = new Button(I18n.get("editor.find"));
        findBtn.setOnAction(e -> toggleSearchPanel());
        
        Button replaceBtn = new Button(I18n.get("editor.replace"));
        replaceBtn.setOnAction(e -> toggleSearchPanel());
        
        Button lintBtn = new Button(I18n.get("editor.lint"));
        lintBtn.setOnAction(e -> runLinter());
        
        Button formatBtn = new Button(I18n.get("editor.format"));
        formatBtn.setOnAction(e -> runFormatter());
        // Disable format button if no formatter is available for this file type
        formatBtn.setDisable(getFormatterInfo() == null);
        if (getFormatterInfo() != null) {
            formatBtn.setTooltip(new Tooltip(I18n.get("editor.format.tooltip", getFormatterInfo().command())));
        }
        
        // Font size controls
        Button zoomInBtn = new Button(I18n.get("editor.zoomIn"));
        zoomInBtn.setOnAction(e -> increaseFontSize());
        
        Button zoomOutBtn = new Button(I18n.get("editor.zoomOut"));
        zoomOutBtn.setOnAction(e -> decreaseFontSize());
        
        Button zoomResetBtn = new Button(I18n.get("editor.zoomReset"));
        zoomResetBtn.setOnAction(e -> resetFontSize());
        
        fontSizeLabel = new Label(currentFontSize + "pt");
        fontSizeLabel.setStyle("-fx-min-width: 40; -fx-alignment: center;");
        
        // Whitespace visualization toggle
        whitespaceToggle = new ToggleButton(I18n.get("editor.whitespace"));
        whitespaceToggle.setTooltip(new Tooltip(I18n.get("editor.whitespace.tooltip")));
        whitespaceToggle.setSelected(false);
        whitespaceToggle.setOnAction(e -> toggleWhitespaceVisualization());
        
        return new ToolBar(saveBtn, saveAsBtn, closeBtn, new Separator(), 
                          findBtn, replaceBtn, new Separator(), 
                          zoomOutBtn, fontSizeLabel, zoomInBtn, zoomResetBtn, new Separator(),
                          lintBtn, formatBtn, new Separator(),
                          whitespaceToggle);
    }
    
    private VBox createSearchPanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #f9f9f9; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
        
        // Search row
        Label searchLabel = new Label(I18n.get("editor.search.find"));
        searchField = new TextField();
        searchField.setPromptText(I18n.get("editor.search.findPrompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        Button findNextBtn = new Button(I18n.get("editor.search.next"));
        findNextBtn.setOnAction(e -> findNext());
        
        Button findPrevBtn = new Button(I18n.get("editor.search.previous"));
        findPrevBtn.setOnAction(e -> findPrevious());
        
        searchResultLabel = new Label("");
        
        HBox searchRow = new HBox(5, searchLabel, searchField, findNextBtn, findPrevBtn, searchResultLabel);
        searchRow.setStyle("-fx-alignment: center-left;");
        
        // Replace row
        Label replaceLabel = new Label(I18n.get("editor.search.replace"));
        replaceField = new TextField();
        replaceField.setPromptText(I18n.get("editor.search.replacePrompt"));
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        
        Button replaceOneBtn = new Button(I18n.get("editor.search.replaceOne"));
        replaceOneBtn.setOnAction(e -> replaceOne());
        
        Button replaceAllBtn = new Button(I18n.get("editor.search.replaceAll"));
        replaceAllBtn.setOnAction(e -> replaceAll());
        
        HBox replaceRow = new HBox(5, replaceLabel, replaceField, replaceOneBtn, replaceAllBtn);
        replaceRow.setStyle("-fx-alignment: center-left;");
        
        // Options row
        regexCheckBox = new CheckBox(I18n.get("editor.search.regex"));
        caseSensitiveCheckBox = new CheckBox(I18n.get("editor.search.caseSensitive"));
        CheckBox selectionOnlyCheckBox = new CheckBox(I18n.get("editor.search.selectionOnly"));
        
        Button closeBtn = new Button(I18n.get("editor.search.close"));
        closeBtn.setOnAction(e -> toggleSearchPanel());
        
        HBox optionsRow = new HBox(10, regexCheckBox, caseSensitiveCheckBox, selectionOnlyCheckBox, closeBtn);
        optionsRow.setStyle("-fx-alignment: center-left;");
        
        // Search on text change
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            performSearch(selectionOnlyCheckBox.isSelected());
        });
        regexCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            performSearch(selectionOnlyCheckBox.isSelected());
        });
        caseSensitiveCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            performSearch(selectionOnlyCheckBox.isSelected());
        });
        selectionOnlyCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            performSearch(newVal);
        });
        
        panel.getChildren().addAll(searchRow, replaceRow, optionsRow);
        return panel;
    }
    
    private void setupKeyboardShortcuts() {
        codeArea.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
                save();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).match(event)) {
                toggleSearchPanel();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN).match(event)) {
                toggleSearchPanel();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                runFormatter();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                whitespaceToggle.setSelected(!whitespaceToggle.isSelected());
                toggleWhitespaceVisualization();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN).match(event)) {
                closeTab();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN).match(event) ||
                       new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN).match(event) ||
                       new KeyCodeCombination(KeyCode.ADD, KeyCombination.SHORTCUT_DOWN).match(event)) {
                increaseFontSize();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN).match(event) ||
                       new KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.SHORTCUT_DOWN).match(event)) {
                decreaseFontSize();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN).match(event) ||
                       new KeyCodeCombination(KeyCode.NUMPAD0, KeyCombination.SHORTCUT_DOWN).match(event)) {
                resetFontSize();
                event.consume();
            }
        });
    }
    
    private void increaseFontSize() {
        if (currentFontSize < MAX_FONT_SIZE) {
            currentFontSize += 2;
            applyFontAndColors();
        }
    }
    
    private void decreaseFontSize() {
        if (currentFontSize > MIN_FONT_SIZE) {
            currentFontSize -= 2;
            applyFontAndColors();
        }
    }
    
    private void resetFontSize() {
        currentFontSize = DEFAULT_FONT_SIZE;
        applyFontAndColors();
    }
    
    private void applyFontAndColors() {
        logger.debug("Applying editor settings - font: {}, size: {}, fg: {}, bg: {}", 
                editorFontFamily, currentFontSize, editorForegroundColor, editorBackgroundColor);
        
        // Set font size, family, and background via inline style
        // InlineCssTextArea uses -fx-background-color directly
        String style = String.format(
            "-fx-font-size: %dpt; " +
            "-fx-font-family: '%s', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-background-color: %s; " +
            "-fx-control-inner-background: %s;",
            currentFontSize, editorFontFamily, editorBackgroundColor, editorBackgroundColor
        );
        codeArea.setStyle(style);
        
        // Apply caret (cursor) color via stylesheet
        applyCaretStyle();
        
        // Re-apply syntax highlighting with current foreground color
        if (!largeFileMode || loadedBytes <= HIGHLIGHT_LIMIT_BYTES) {
            try {
                codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
            } catch (Exception e) {
                logger.warn("Failed to re-apply highlighting", e);
            }
        }
        
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(currentFontSize + "pt");
        }
        statusLabel.setText(I18n.get("editor.status.fontSize", currentFontSize));
    }
    
    private void applyCaretStyle() {
        // Measure actual character width for block cursor
        double charWidth;
        try {
            javafx.scene.text.Text measure = new javafx.scene.text.Text("M");
            measure.setFont(javafx.scene.text.Font.font(editorFontFamily, currentFontSize));
            charWidth = measure.getLayoutBounds().getWidth();
            if (charWidth <= 0) charWidth = currentFontSize * 0.6;
        } catch (Exception e) {
            charWidth = currentFontSize * 0.6;
        }
        
        // Determine stroke width based on cursor style
        double strokeWidth;
        boolean isBlock = false;
        switch (editorCursorStyle.toUpperCase()) {
            case "LINE":
                strokeWidth = 2.0;
                break;
            case "UNDERSCORE":
                strokeWidth = 2.0;
                break;
            case "BLOCK":
            default:
                strokeWidth = charWidth; // Full character width for true block cursor
                isBlock = true;
                break;
        }
        
        logger.info("Applying caret style: color={}, style={}, width={}, charWidth={}", 
                editorCursorColor, editorCursorStyle, strokeWidth, charWidth);
        
        // Apply caret style using Platform.runLater to ensure scene is ready
        final double finalStrokeWidth = strokeWidth;
        final boolean finalIsBlock = isBlock;
        Platform.runLater(() -> {
            try {
                // Find and style the caret directly
                codeArea.lookupAll(".caret").forEach(node -> {
                    if (node instanceof javafx.scene.shape.Path) {
                        javafx.scene.shape.Path caret = (javafx.scene.shape.Path) node;
                        caret.setStroke(javafx.scene.paint.Color.web(editorCursorColor));
                        caret.setStrokeWidth(finalStrokeWidth);
                        caret.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.BUTT);
                        // Shift block cursor right so it covers the character after the caret position
                        if (finalIsBlock) {
                            caret.setTranslateX(finalStrokeWidth / 2.0);
                        } else {
                            caret.setTranslateX(0);
                        }
                        logger.debug("Styled caret directly: color={}, width={}, isBlock={}", 
                                editorCursorColor, finalStrokeWidth, finalIsBlock);
                    }
                });
                
                // Also set via CSS as fallback
                String caretCss = String.format(java.util.Locale.US,
                    ".caret { -fx-stroke: %s; -fx-stroke-width: %.1f; -fx-stroke-line-cap: butt; }",
                    editorCursorColor, finalStrokeWidth
                );
                codeArea.getStylesheets().removeIf(s -> s.startsWith("data:"));
                String dataUri = "data:text/css;charset=utf-8," + java.net.URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
                codeArea.getStylesheets().add(dataUri);
            } catch (Exception e) {
                logger.warn("Failed to apply caret style", e);
            }
        });
    }
    
    
    /**
     * Loads the next chunk of a large file and appends it to the editor.
     */
    private void loadNextChunk() {
        if (!largeFileMode || largeFilePath == null) {
            return;
        }
        long remaining = fileSizeBytes - loadedBytes;
        if (remaining <= 0) {
            if (loadMoreButton != null) {
                loadMoreButton.setDisable(true);
                loadMoreButton.setText(I18n.get("editor.status.ready"));
            }
            return;
        }
        int toRead = (int) Math.min(CHUNK_SIZE_BYTES, remaining);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(largeFilePath.toFile(), "r")) {
            raf.seek(loadedBytes);
            byte[] buffer = new byte[toRead];
            int read = raf.read(buffer);
            if (read > 0) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                codeArea.appendText(chunk);
                loadedBytes += read;
                if (loadedBytes <= HIGHLIGHT_LIMIT_BYTES) {
                    codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
                }
                updateLargeFileStatus();
            }
        } catch (Exception ex) {
            logger.error("Failed to load next chunk", ex);
            showError("Load error", ex.getMessage());
        }
    }
    
    private void updateLargeFileStatus() {
        double percent = fileSizeBytes == 0 ? 0 : (loadedBytes * 100.0 / fileSizeBytes);
        statusLabel.setText(String.format("Large file mode (read-only) - Loaded %.1f%% (%s / %s)", 
                percent, humanReadableBytes(loadedBytes), humanReadableBytes(fileSizeBytes)));
        if (loadMoreButton != null) {
            loadMoreButton.setDisable(loadedBytes >= fileSizeBytes);
        }
    }
    
    private String humanReadableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    private ContextMenu createContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem cutItem = new MenuItem(I18n.get("editor.context.cut"));
        cutItem.setOnAction(e -> codeArea.cut());
        
        MenuItem copyItem = new MenuItem(I18n.get("editor.context.copy"));
        copyItem.setOnAction(e -> codeArea.copy());
        
        MenuItem pasteItem = new MenuItem(I18n.get("editor.context.paste"));
        pasteItem.setOnAction(e -> codeArea.paste());
        
        MenuItem deleteItem = new MenuItem(I18n.get("editor.context.delete"));
        deleteItem.setOnAction(e -> codeArea.replaceSelection(""));
        
        contextMenu.getItems().addAll(cutItem, copyItem, pasteItem, deleteItem);
        
        MenuItem findItem = new MenuItem(I18n.get("editor.context.find"));
        findItem.setOnAction(e -> showFind());
        contextMenu.getItems().add(new SeparatorMenuItem());
        contextMenu.getItems().add(findItem);
        
        MenuItem selectAllItem = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAllItem.setOnAction(e -> codeArea.selectAll());
        contextMenu.getItems().add(new SeparatorMenuItem());
        contextMenu.getItems().add(selectAllItem);
        
        // Whitespace visualization
        CheckMenuItem whitespaceItem = new CheckMenuItem(I18n.get("editor.whitespace"));
        whitespaceItem.setSelected(showWhitespace);
        whitespaceItem.setOnAction(e -> {
            if (whitespaceToggle != null) {
                whitespaceToggle.setSelected(!whitespaceToggle.isSelected());
                toggleWhitespaceVisualization();
            }
        });
        contextMenu.getItems().add(new SeparatorMenuItem());
        contextMenu.getItems().add(whitespaceItem);
        
        // Line ending submenu
        Menu lineEndingMenu = new Menu(I18n.get("editor.lineEnding.menu"));
        MenuItem lfItem = new MenuItem("LF - " + I18n.get("editor.lineEnding.unix"));
        lfItem.setOnAction(e -> convertLineEndings(LineEnding.LF));
        MenuItem crlfItem = new MenuItem("CRLF - " + I18n.get("editor.lineEnding.windows"));
        crlfItem.setOnAction(e -> convertLineEndings(LineEnding.CRLF));
        lineEndingMenu.getItems().addAll(lfItem, crlfItem);
        contextMenu.getItems().add(lineEndingMenu);
        
        // Enable/disable based on selection; keep whitespace checkbox in sync
        contextMenu.setOnShowing(e -> {
            boolean hasSelection = codeArea.getSelection().getLength() > 0;
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            if (whitespaceItem != null) {
                whitespaceItem.setSelected(showWhitespace);
            }
        });
        
        return contextMenu;
    }
    
    private void closeTab() {
        if (isModified) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.get("editor.close.title"));
            alert.setHeaderText(I18n.get("editor.close.header"));
            alert.setContentText(I18n.get("editor.close.unsaved"));
            
            ButtonType saveBtn = new ButtonType(I18n.get("editor.close.save"));
            ButtonType discardBtn = new ButtonType(I18n.get("editor.close.discard"));
            ButtonType cancelBtn = new ButtonType(I18n.get("editor.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            
            alert.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);
            
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent()) {
                if (result.get() == saveBtn) {
                    save();
                    removeTabSafely();
                } else if (result.get() == discardBtn) {
                    removeTabSafely();
                }
                // Cancel: do nothing
            }
        } else {
            removeTabSafely();
        }
    }
    
    private void removeTabSafely() {
        TabPane tabPane = getTabPane();
        int currentIndex = tabPane.getTabs().indexOf(this);
        
        // Suppress QuickConnect if + tab might be selected
        de.kortty.ui.MainWindow.suppressNextQuickConnect();
        
        // Select a different tab before removing this one to avoid selecting the "+" tab
        if (currentIndex > 0) {
            // Select previous tab
            tabPane.getSelectionModel().select(currentIndex - 1);
        } else if (tabPane.getTabs().size() > 1) {
            // We're at index 0, select next tab (if it's not the "+" tab)
            Tab nextTab = tabPane.getTabs().get(1);
            if (nextTab.getText() != null && !nextTab.getText().equals("+")) {
                tabPane.getSelectionModel().select(1);
            }
        }
        
        // Now remove this tab
        tabPane.getTabs().remove(this);
    }
    
    /**
     * Shows the find/replace panel.
     */
    public void showFind() {
        BorderPane root = (BorderPane) getContent();
        VBox topArea = (VBox) root.getTop();
        VBox searchPanel = (VBox) topArea.getChildren().get(1);
        
        if (!searchPanel.isVisible()) {
            searchPanel.setVisible(true);
            searchPanel.setManaged(true);
        }
        searchField.requestFocus();
    }
    
    private void toggleSearchPanel() {
        BorderPane root = (BorderPane) getContent();
        VBox topArea = (VBox) root.getTop();
        VBox searchPanel = (VBox) topArea.getChildren().get(1);
        
        boolean isVisible = searchPanel.isVisible();
        searchPanel.setVisible(!isVisible);
        searchPanel.setManaged(!isVisible);
        
        if (!isVisible) {
            searchField.requestFocus();
        }
    }
    
    private void performSearch(boolean selectionOnly) {
        String searchText = searchField.getText();
        if (searchText == null || searchText.isEmpty()) {
            searchMatches.clear();
            searchResultLabel.setText("");
            return;
        }
        
        searchMatches.clear();
        String content;
        int searchOffset = 0;
        
        if (selectionOnly && codeArea.getSelection().getLength() > 0) {
            // Search only in selection
            content = codeArea.getSelectedText();
            searchOffset = codeArea.getSelection().getStart();
        } else {
            content = codeArea.getText();
        }
        
        try {
            if (regexCheckBox.isSelected()) {
                int flags = caseSensitiveCheckBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(searchText, flags);
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    searchMatches.add(matcher.start() + searchOffset);
                }
            } else {
                String searchLower = caseSensitiveCheckBox.isSelected() ? searchText : searchText.toLowerCase();
                String contentLower = caseSensitiveCheckBox.isSelected() ? content : content.toLowerCase();
                int index = 0;
                while ((index = contentLower.indexOf(searchLower, index)) != -1) {
                    searchMatches.add(index + searchOffset);
                    index += searchText.length();
                }
            }
            
            if (!searchMatches.isEmpty()) {
                currentSearchIndex = 0;
                highlightMatch(currentSearchIndex);
                searchResultLabel.setText(String.format("%d/%d", currentSearchIndex + 1, searchMatches.size()));
            } else {
                searchResultLabel.setText(I18n.get("editor.search.noMatches"));
            }
        } catch (Exception e) {
            searchResultLabel.setText(I18n.get("editor.search.invalidRegex"));
        }
    }
    
    private void findNext() {
        if (searchMatches.isEmpty()) {
            performSearch(false);
            return;
        }
        
        currentSearchIndex = (currentSearchIndex + 1) % searchMatches.size();
        highlightMatch(currentSearchIndex);
        searchResultLabel.setText(String.format("%d/%d", currentSearchIndex + 1, searchMatches.size()));
    }
    
    private void findPrevious() {
        if (searchMatches.isEmpty()) {
            performSearch(false);
            return;
        }
        
        currentSearchIndex = (currentSearchIndex - 1 + searchMatches.size()) % searchMatches.size();
        highlightMatch(currentSearchIndex);
        searchResultLabel.setText(String.format("%d/%d", currentSearchIndex + 1, searchMatches.size()));
    }
    
    private void highlightMatch(int index) {
        if (index < 0 || index >= searchMatches.size()) return;
        
        int start = searchMatches.get(index);
        String searchText = searchField.getText();
        int length = searchText.length();
        
        codeArea.selectRange(start, start + length);
        codeArea.requestFollowCaret();
    }
    
    private void replaceOne() {
        if (searchMatches.isEmpty() || currentSearchIndex < 0) {
            performSearch(false);
            return;
        }
        
        int start = searchMatches.get(currentSearchIndex);
        String searchText = searchField.getText();
        String replaceText = replaceField.getText();
        
        codeArea.replaceText(start, start + searchText.length(), replaceText);
        
        // Re-search after replacement
        performSearch(false);
    }
    
    private void replaceAll() {
        String replaceText = replaceField.getText();
        if (searchMatches.isEmpty()) {
            performSearch(false);
            return;
        }
        
        int replacedCount = searchMatches.size();
        
        // Replace from end to start to maintain indices
        for (int i = searchMatches.size() - 1; i >= 0; i--) {
            int start = searchMatches.get(i);
            String searchText = searchField.getText();
            codeArea.replaceText(start, start + searchText.length(), replaceText);
        }
        
        statusLabel.setText(I18n.get("editor.status.replaced", replacedCount));
        performSearch(false);
    }
    
    private void save() {
        try {
            // If whitespace visualization is active, use the stored original text
            String content = showWhitespace && originalText != null ? originalText : codeArea.getText();
            
            // Apply the correct line endings for saving
            if (detectedLineEnding == LineEnding.CRLF) {
                // Normalize to LF first, then convert to CRLF
                content = content.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
            }
            
            if (isRemoteFile) {
                // Save to remote server
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                sftpSession.uploadFileBytes(bytes, remotePath);
                isModified = false;
                updateTitle();
                statusLabel.setText(I18n.get("editor.status.saved", remotePath));
                logger.info("Saved remote file: {}", remotePath);
            } else {
                // Save to local file
                Files.writeString(localPath, content, StandardCharsets.UTF_8);
                isModified = false;
                updateTitle();
                statusLabel.setText(I18n.get("editor.status.saved", localPath.toString()));
                logger.info("Saved local file: {}", localPath);
            }
        } catch (Exception e) {
            logger.error("Failed to save file", e);
            showError(I18n.get("error.title"), I18n.get("editor.error.save", e.getMessage()));
        }
    }
    
    private void saveAs() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("editor.saveAs.title"));
        
        if (isRemoteFile) {
            // For remote files, save locally
            fileChooser.setInitialFileName(Paths.get(remotePath).getFileName().toString());
        } else {
            fileChooser.setInitialFileName(localPath.getFileName().toString());
            fileChooser.setInitialDirectory(localPath.getParent().toFile());
        }
        
        File file = fileChooser.showSaveDialog(getTabPane().getScene().getWindow());
        if (file != null) {
            try {
                String content = codeArea.getText();
                Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
                
                if (!isRemoteFile) {
                    // Update local path
                    localPath = file.toPath();
                    setText(localPath.getFileName().toString());
                }
                
                isModified = false;
                updateTitle();
                statusLabel.setText(I18n.get("editor.status.saved", file.getAbsolutePath()));
                logger.info("Saved file as: {}", file.getAbsolutePath());
            } catch (Exception e) {
                logger.error("Failed to save file", e);
                showError(I18n.get("error.title"), I18n.get("editor.error.save", e.getMessage()));
            }
        }
    }
    
    // ==================== Whitespace Visualization ====================
    
    /**
     * Unicode symbols used to represent invisible characters.
     */
    private static final char VISIBLE_SPACE = '\u00B7';     // middle dot ·
    private static final char VISIBLE_TAB = '\u2192';       // rightwards arrow →
    private static final char VISIBLE_CR = '\u240D';        // symbol for carriage return ␍
    private static final char VISIBLE_LF = '\u2193';        // downwards arrow ↓ (shown at line end)
    private static final char VISIBLE_NBSP = '\u2423';      // open box ␣ (non-breaking space)
    
    /**
     * Toggles the visibility of whitespace and special characters.
     * When enabled, the text is replaced with a visual representation.
     * When disabled, the original text is restored.
     */
    private String originalText = null;
    
    private void toggleWhitespaceVisualization() {
        showWhitespace = whitespaceToggle.isSelected();
        
        if (showWhitespace) {
            // Store original text and replace with visible version
            originalText = codeArea.getText();
            String visible = makeWhitespaceVisible(originalText);
            
            // Temporarily suppress modification tracking
            boolean wasModified = isModified;
            codeArea.replaceText(visible);
            isModified = wasModified;
            updateTitle();
            
            codeArea.setEditable(false); // Prevent editing while visualized
            statusLabel.setText(I18n.get("editor.whitespace.active"));
        } else {
            // Restore original text
            if (originalText != null) {
                boolean wasModified = isModified;
                codeArea.replaceText(originalText);
                isModified = wasModified;
                updateTitle();
                originalText = null;
            }
            codeArea.setEditable(true);
            statusLabel.setText(I18n.get("editor.status.ready"));
        }
    }
    
    /**
     * Replaces invisible characters with their visible Unicode equivalents.
     */
    private String makeWhitespaceVisible(String text) {
        StringBuilder sb = new StringBuilder(text.length() + text.length() / 10);
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case ' ':
                    sb.append(VISIBLE_SPACE);
                    break;
                case '\t':
                    sb.append(VISIBLE_TAB);
                    // Pad with spaces to approximate tab width
                    sb.append("   ");
                    break;
                case '\r':
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        sb.append(VISIBLE_CR).append(VISIBLE_LF);
                        i++; // skip the \n
                        sb.append('\n');
                    } else {
                        sb.append(VISIBLE_CR).append('\n');
                    }
                    break;
                case '\n':
                    sb.append(VISIBLE_LF).append('\n');
                    break;
                case '\u00A0': // non-breaking space
                    sb.append(VISIBLE_NBSP);
                    break;
                case '\u200B': // zero-width space
                    sb.append("[ZWS]");
                    break;
                case '\u200C': // zero-width non-joiner
                    sb.append("[ZWNJ]");
                    break;
                case '\u200D': // zero-width joiner
                    sb.append("[ZWJ]");
                    break;
                case '\uFEFF': // BOM / zero-width no-break space
                    sb.append("[BOM]");
                    break;
                default:
                    if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                        // Show control characters as [0xNN]
                        sb.append(String.format("[0x%02X]", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
    
    // ==================== Line Ending Detection & Conversion ====================
    
    /**
     * Detects the line ending style of the given text.
     */
    private LineEnding detectLineEnding(String text) {
        int crlfCount = 0;
        int lfCount = 0;
        int crCount = 0;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlfCount++;
                    i++; // skip the \n
                } else {
                    crCount++;
                }
            } else if (c == '\n') {
                lfCount++;
            }
        }
        
        int total = crlfCount + lfCount + crCount;
        if (total == 0) return LineEnding.LF; // default for new/empty files
        
        if (crlfCount > 0 && lfCount == 0 && crCount == 0) return LineEnding.CRLF;
        if (lfCount > 0 && crlfCount == 0 && crCount == 0) return LineEnding.LF;
        if (crCount > 0 && crlfCount == 0 && lfCount == 0) return LineEnding.CR;
        return LineEnding.MIXED;
    }
    
    /**
     * Returns a display name for the current line ending style.
     */
    private String getLineEndingDisplayName() {
        return switch (detectedLineEnding) {
            case LF -> "LF (Unix/macOS)";
            case CRLF -> "CRLF (Windows)";
            case CR -> "CR (Classic Mac)";
            case MIXED -> I18n.get("editor.lineEnding.mixed");
        };
    }
    
    /**
     * Shows a context menu for choosing the line ending format.
     */
    private void showLineEndingMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem lfItem = new MenuItem("LF - Unix/macOS/Linux");
        lfItem.setOnAction(e -> convertLineEndings(LineEnding.LF));
        if (detectedLineEnding == LineEnding.LF) {
            lfItem.setStyle("-fx-font-weight: bold;");
        }
        
        MenuItem crlfItem = new MenuItem("CRLF - Windows");
        crlfItem.setOnAction(e -> convertLineEndings(LineEnding.CRLF));
        if (detectedLineEnding == LineEnding.CRLF) {
            crlfItem.setStyle("-fx-font-weight: bold;");
        }
        
        menu.getItems().addAll(lfItem, crlfItem);
        
        // Show below the label
        javafx.geometry.Bounds bounds = lineEndingLabel.localToScreen(lineEndingLabel.getBoundsInLocal());
        if (bounds != null) {
            menu.show(lineEndingLabel, bounds.getMinX(), bounds.getMinY() - 50);
        } else {
            menu.show(lineEndingLabel, javafx.geometry.Side.TOP, 0, 0);
        }
    }
    
    /**
     * Converts all line endings in the editor to the specified format.
     */
    private void convertLineEndings(LineEnding target) {
        if (target == detectedLineEnding && detectedLineEnding != LineEnding.MIXED) {
            return; // Already in the target format
        }
        
        // Disable whitespace visualization during conversion
        if (showWhitespace) {
            whitespaceToggle.setSelected(false);
            toggleWhitespaceVisualization();
        }
        
        String text = codeArea.getText();
        
        // First normalize all line endings to LF
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        
        // Then convert to target
        String converted = switch (target) {
            case CRLF -> normalized.replace("\n", "\r\n");
            case CR -> normalized.replace("\n", "\r");
            default -> normalized; // LF is already normalized
        };
        
        int caretPos = Math.min(codeArea.getCaretPosition(), converted.length());
        codeArea.replaceText(converted);
        codeArea.moveTo(caretPos);
        
        detectedLineEnding = target;
        lineEndingLabel.setText(getLineEndingDisplayName());
        statusLabel.setText(I18n.get("editor.lineEnding.converted", getLineEndingDisplayName()));
        
        logger.info("Converted line endings to {}", target);
    }
    
    // ==================== Code Formatter ====================
    
    /**
     * Holds information about a code formatter for a specific file type.
     * @param command the CLI command (first word) to check availability
     * @param args the full command + args (stdin-based); null if file-based
     * @param fileArgs the full command + args (file-based); null if stdin-based
     * @param installHint how to install the tool (e.g. "brew install shfmt")
     */
    private record FormatterInfo(String command, String[] stdinArgs, String[] fileArgs, String installHint) {}
    
    /**
     * Returns formatter information for the current file type, or null if no formatter is known.
     */
    private FormatterInfo getFormatterInfo() {
        return switch (fileType) {
            case JSON -> new FormatterInfo("python3",
                new String[]{"python3", "-m", "json.tool"}, null,
                "python3 (usually pre-installed on macOS/Linux)");
            case XML -> new FormatterInfo("xmllint",
                new String[]{"xmllint", "--format", "-"}, null,
                "brew install libxml2  /  apt install libxml2-utils");
            case YAML, ANSIBLE_YAML -> new FormatterInfo("yq",
                new String[]{"yq", "eval", ".", "-"}, null,
                "brew install yq  /  snap install yq");
            case TOML -> new FormatterInfo("taplo",
                new String[]{"taplo", "fmt", "-"}, null,
                "brew install taplo  /  cargo install taplo-cli");
            case PYTHON -> new FormatterInfo("black",
                new String[]{"black", "-q", "-"}, null,
                "pip install black  /  brew install black");
            case PERL -> new FormatterInfo("perltidy",
                new String[]{"perltidy", "-st"}, null,
                "brew install perltidy  /  cpan Perl::Tidy");
            case RUBY -> new FormatterInfo("rubocop",
                null, new String[]{"rubocop", "-a", "--stderr", "--stdin"},
                "gem install rubocop");
            case SHELL -> new FormatterInfo("shfmt",
                new String[]{"shfmt"}, null,
                "brew install shfmt  /  go install mvdan.cc/sh/v3/cmd/shfmt@latest");
            case HTML -> new FormatterInfo("prettier",
                new String[]{"prettier", "--parser", "html"}, null,
                "npm install -g prettier");
            case CSS -> new FormatterInfo("prettier",
                new String[]{"prettier", "--parser", "css"}, null,
                "npm install -g prettier");
            case JAVASCRIPT -> new FormatterInfo("prettier",
                new String[]{"prettier", "--parser", "typescript"}, null,
                "npm install -g prettier");
            case JAVA -> new FormatterInfo("google-java-format",
                new String[]{"google-java-format", "-"}, null,
                "brew install google-java-format");
            case GO -> new FormatterInfo("gofmt",
                new String[]{"gofmt"}, null,
                "go (included with Go installation)");
            case RUST -> new FormatterInfo("rustfmt",
                new String[]{"rustfmt"}, null,
                "rustup component add rustfmt");
            case SQL -> new FormatterInfo("sql-formatter",
                new String[]{"sql-formatter"}, null,
                "npm install -g sql-formatter");
            case TERRAFORM -> new FormatterInfo("terraform",
                null, new String[]{"terraform", "fmt"},
                "brew install terraform  /  https://developer.hashicorp.com/terraform/install");
            default -> null;
        };
    }
    
    /**
     * Formats the current editor content using an external CLI formatter.
     * Supports both stdin-based and file-based formatters.
     * Preserves cursor position and undo history.
     */
    private void runFormatter() {
        FormatterInfo info = getFormatterInfo();
        
        if (info == null) {
            showInfo(I18n.get("editor.format.title"), 
                I18n.get("editor.format.notSupported", fileType.name()));
            return;
        }
        
        if (!checkCommandAvailable(info.command())) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(I18n.get("editor.format.title"));
            alert.setHeaderText(I18n.get("editor.format.notInstalled", info.command()));
            alert.setContentText(I18n.get("editor.format.installHint", info.installHint()));
            alert.showAndWait();
            return;
        }
        
        statusLabel.setText(I18n.get("editor.format.running"));
        
        // Run in background to not block UI
        Thread formatThread = new Thread(() -> {
            try {
                String originalText = codeArea.getText();
                String formattedText;
                
                if (info.stdinArgs() != null) {
                    // stdin-based formatter: pipe content via stdin, read formatted output from stdout
                    formattedText = runStdinFormatter(info.stdinArgs(), originalText);
                } else if (info.fileArgs() != null) {
                    // file-based formatter: write to temp file, run formatter, read back
                    formattedText = runFileFormatter(info.fileArgs(), originalText);
                } else {
                    throw new Exception("No formatter args configured");
                }
                
                if (formattedText != null && !formattedText.equals(originalText)) {
                    final String result = formattedText;
                    Platform.runLater(() -> {
                        int caretPos = Math.min(codeArea.getCaretPosition(), result.length());
                        codeArea.replaceText(result);
                        codeArea.moveTo(caretPos);
                        statusLabel.setText(I18n.get("editor.format.success"));
                    });
                } else {
                    Platform.runLater(() -> statusLabel.setText(I18n.get("editor.format.noChanges")));
                }
            } catch (Exception e) {
                logger.error("Formatter failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("editor.format.failed"));
                    showError(I18n.get("editor.format.title"), 
                        I18n.get("editor.format.error", e.getMessage()));
                });
            }
        }, "CodeFormatter");
        formatThread.setDaemon(true);
        formatThread.start();
    }
    
    /**
     * Runs a stdin-based formatter: pipes content to stdin, reads formatted output from stdout.
     */
    private String runStdinFormatter(String[] args, String input) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        
        // Write input to stdin in a separate thread to prevent deadlock
        Thread writerThread = new Thread(() -> {
            try (var os = process.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                logger.debug("Error writing to formatter stdin: {}", e.getMessage());
            }
        });
        writerThread.setDaemon(true);
        writerThread.start();
        
        // Read stdout and stderr
        String stdout;
        String stderr;
        try (var stdoutStream = process.getInputStream();
             var stderrStream = process.getErrorStream()) {
            stdout = new String(stdoutStream.readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(stderrStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        
        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("Formatter timed out after 15 seconds");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorMsg = stderr.isEmpty() ? "exit code " + exitCode : stderr.trim();
            throw new Exception(errorMsg);
        }
        
        return stdout;
    }
    
    /**
     * Runs a file-based formatter: writes to temp file, runs formatter on it, reads back.
     */
    private String runFileFormatter(String[] args, String input) throws Exception {
        Path tempFile = Files.createTempFile("kortty_format_", getFileExtension());
        try {
            Files.writeString(tempFile, input, StandardCharsets.UTF_8);
            
            // Build command with temp file appended
            java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(args));
            command.add(tempFile.toString());
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            
            String stderr;
            try (var stderrStream = process.getErrorStream()) {
                stderr = new String(stderrStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new Exception("Formatter timed out after 15 seconds");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0 && !stderr.isEmpty()) {
                throw new Exception(stderr.trim());
            }
            
            // Read the formatted file back
            return Files.readString(tempFile, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    private void runLinter() {
        // Check if linter is available for this file type
        Optional<String> linterCommand = getLinterCommand();
        
        if (linterCommand.isEmpty()) {
            showInfo(I18n.get("editor.lint.title"), I18n.get("editor.lint.notAvailable", fileType.name()));
            return;
        }
        
        try {
            // Save to temporary file
            Path tempFile = Files.createTempFile("kortty_lint_", getFileExtension());
            Files.writeString(tempFile, codeArea.getText(), StandardCharsets.UTF_8);
            
            // Run linter
            ProcessBuilder pb = new ProcessBuilder(linterCommand.get().split(" "));
            pb.command().add(tempFile.toString());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            int exitCode = process.waitFor();
            
            String lintOutput = output.toString(StandardCharsets.UTF_8);
            
            // Clean up
            Files.deleteIfExists(tempFile);
            
            if (exitCode == 0) {
                showInfo(I18n.get("editor.lint.title"), I18n.get("editor.lint.success"));
            } else {
                showError(I18n.get("editor.lint.title"), I18n.get("editor.lint.errors") + "\n\n" + lintOutput);
            }
            
        } catch (Exception e) {
            logger.error("Failed to run linter", e);
            showError(I18n.get("error.title"), I18n.get("editor.lint.error", e.getMessage()));
        }
    }
    
    private Optional<String> getLinterCommand() {
        return switch (fileType) {
            case ANSIBLE_YAML -> checkCommandAvailable("ansible-lint") ? Optional.of("ansible-lint")
                : (checkCommandAvailable("yamllint") ? Optional.of("yamllint") : Optional.empty());
            case YAML -> checkCommandAvailable("yamllint") ? Optional.of("yamllint") : Optional.empty();
            case JSON -> checkCommandAvailable("jsonlint") ? Optional.of("jsonlint") : Optional.empty();
            case XML -> checkCommandAvailable("xmllint") ? Optional.of("xmllint --noout") : Optional.empty();
            case MARKDOWN -> checkCommandAvailable("markdownlint") ? Optional.of("markdownlint") : Optional.empty();
            case PYTHON -> checkCommandAvailable("pylint") ? Optional.of("pylint --output-format=text") 
                : (checkCommandAvailable("flake8") ? Optional.of("flake8") : Optional.empty());
            case PERL -> checkCommandAvailable("perl") ? Optional.of("perl -c") : Optional.empty();
            case RUBY -> checkCommandAvailable("ruby") ? Optional.of("ruby -c") : Optional.empty();
            case SHELL -> checkCommandAvailable("shellcheck") ? Optional.of("shellcheck") : Optional.empty();
            case JAVASCRIPT -> checkCommandAvailable("eslint") ? Optional.of("eslint") : Optional.empty();
            case GO -> checkCommandAvailable("go") ? Optional.of("go vet") : Optional.empty();
            case RUST -> checkCommandAvailable("rustc") ? Optional.of("rustc --edition 2021 --crate-type lib") : Optional.empty();
            case TERRAFORM -> checkCommandAvailable("terraform") ? Optional.of("terraform validate") : Optional.empty();
            case DOCKERFILE -> checkCommandAvailable("hadolint") ? Optional.of("hadolint") : Optional.empty();
            case PUPPET -> checkCommandAvailable("puppet") ? Optional.of("puppet parser validate") : Optional.empty();
            case CFENGINE3 -> checkCommandAvailable("cf-promises") ? Optional.of("cf-promises --full-check") : Optional.empty();
            case JINJA2 -> checkCommandAvailable("j2lint") ? Optional.of("j2lint")
                : (checkCommandAvailable("djlint") ? Optional.of("djlint --lint") : Optional.empty());
            default -> Optional.empty();
        };
    }
    
    private boolean checkCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("command", "-v", command);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getFileExtension() {
        return switch (fileType) {
            case XML -> ".xml";
            case JSON -> ".json";
            case YAML, ANSIBLE_YAML -> ".yml";
            case TOML -> ".toml";
            case MARKDOWN -> ".md";
            case INI -> ".ini";
            case CFG -> ".cfg";
            case JINJA2 -> ".j2";
            case PYTHON -> ".py";
            case PERL -> ".pl";
            case RUBY -> ".rb";
            case SHELL -> ".sh";
            case HTML -> ".html";
            case CSS -> ".css";
            case JAVASCRIPT -> ".js";
            case JAVA -> ".java";
            case GO -> ".go";
            case RUST -> ".rs";
            case SQL -> ".sql";
            case DOCKERFILE -> ".dockerfile";
            case TERRAFORM -> ".tf";
            case PUPPET -> ".pp";
            case CFENGINE3 -> ".cf";
            default -> ".txt";
        };
    }
    
    private void updateTitle() {
        String title = getText();
        if (title.endsWith(" *")) {
            title = title.substring(0, title.length() - 2);
        }
        if (isModified) {
            setText(title + " *");
        } else {
            setText(title);
        }
    }
    
    private FileType detectFileType(String filename) {
        String lower = filename.toLowerCase();
        
        if (lower.endsWith(".xml")) return FileType.XML;
        if (lower.endsWith(".json")) return FileType.JSON;
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            // Check if it's an Ansible file (playbook, role, task, handler, etc.)
            if (lower.contains("playbook") || lower.contains("ansible") 
                    || lower.contains("tasks") || lower.contains("handlers") 
                    || lower.contains("roles") || lower.contains("vars")
                    || lower.contains("defaults") || lower.contains("inventory")) {
                return FileType.ANSIBLE_YAML;
            }
            return FileType.YAML;
        }
        if (lower.endsWith(".toml")) return FileType.TOML;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return FileType.MARKDOWN;
        if (lower.endsWith(".ini")) return FileType.INI;
        if (lower.endsWith(".cfg") || lower.endsWith(".conf")) return FileType.CFG;
        if (lower.endsWith(".j2") || lower.endsWith(".jinja") || lower.endsWith(".jinja2")) return FileType.JINJA2;
        if (lower.endsWith(".py") || lower.endsWith(".pyw")) return FileType.PYTHON;
        if (lower.endsWith(".pl") || lower.endsWith(".pm") || lower.endsWith(".perl")) return FileType.PERL;
        if (lower.endsWith(".rb") || lower.endsWith(".rake") || lower.endsWith(".gemspec")) return FileType.RUBY;
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") || lower.endsWith(".ksh") || lower.endsWith(".fish")) return FileType.SHELL;
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) return FileType.HTML;
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) return FileType.CSS;
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".jsx")) return FileType.JAVASCRIPT;
        if (lower.endsWith(".java")) return FileType.JAVA;
        if (lower.endsWith(".go")) return FileType.GO;
        if (lower.endsWith(".rs")) return FileType.RUST;
        if (lower.endsWith(".sql")) return FileType.SQL;
        if (lower.equals("dockerfile") || lower.endsWith(".dockerfile")) return FileType.DOCKERFILE;
        if (lower.endsWith(".tf") || lower.endsWith(".tfvars")) return FileType.TERRAFORM;
        if (lower.endsWith(".pp")) return FileType.PUPPET;
        if (lower.endsWith(".cf")) return FileType.CFENGINE3;
        
        return FileType.PLAIN_TEXT;
    }
    
    // Syntax highlighting colors (inline CSS)
    private static final String STYLE_XML_ELEMENT = "-fx-fill: #0000cc; -fx-font-weight: bold;";
    private static final String STYLE_STRING = "-fx-fill: #008800;";
    private static final String STYLE_NUMBER = "-fx-fill: #0066cc;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #cc00cc; -fx-font-weight: bold;";
    private static final String STYLE_BRACE = "-fx-fill: #cc6600; -fx-font-weight: bold;";
    private static final String STYLE_PUNCTUATION = "-fx-fill: #666666;";
    private static final String STYLE_KEY = "-fx-fill: #cc0000; -fx-font-weight: bold;";
    private static final String STYLE_SECTION = "-fx-fill: #9900cc; -fx-font-weight: bold;";
    private static final String STYLE_HEADER = "-fx-fill: #0066cc; -fx-font-weight: bold;";
    private static final String STYLE_BOLD = "-fx-font-weight: bold;";
    private static final String STYLE_ITALIC = "-fx-font-style: italic;";
    private static final String STYLE_CODE = "-fx-fill: #cc0066;";
    private static final String STYLE_LINK = "-fx-fill: #0066cc; -fx-underline: true;";
    private static final String STYLE_LIST = "-fx-fill: #cc6600;";
    private static final String STYLE_JINJA = "-fx-fill: #9900cc;";
    private static final String STYLE_COMMENT = "-fx-fill: #888888; -fx-font-style: italic;";
    private static final String STYLE_KEYWORD = "-fx-fill: #7700bb; -fx-font-weight: bold;";
    private static final String STYLE_BUILTIN = "-fx-fill: #0077aa;";
    private static final String STYLE_DECORATOR = "-fx-fill: #aa5500;";
    private static final String STYLE_VARIABLE = "-fx-fill: #bb0066;";
    private static final String STYLE_OPERATOR = "-fx-fill: #444444; -fx-font-weight: bold;";
    private static final String STYLE_FUNCTION = "-fx-fill: #0055aa;";
    
    // Computed at runtime from settings
    private String getPlainTextStyle() {
        return "-fx-fill: " + editorForegroundColor + ";";
    }
    
    private StyleSpans<String> computeHighlighting(String text) {
        return switch (fileType) {
            case XML -> computeXmlHighlighting(text);
            case JSON -> computeJsonHighlighting(text);
            case YAML, ANSIBLE_YAML -> computeYamlHighlighting(text);
            case TOML -> computeTomlHighlighting(text);
            case MARKDOWN -> computeMarkdownHighlighting(text);
            case INI, CFG -> computeIniHighlighting(text);
            case JINJA2 -> computeJinja2Highlighting(text);
            case PYTHON -> computePythonHighlighting(text);
            case PERL -> computePerlHighlighting(text);
            case RUBY -> computeRubyHighlighting(text);
            case SHELL -> computeShellHighlighting(text);
            case HTML -> computeHtmlHighlighting(text);
            case CSS -> computeCssHighlighting(text);
            case JAVASCRIPT, JAVA, GO, RUST -> computeGenericCodeHighlighting(text);
            case SQL -> computeSqlHighlighting(text);
            case DOCKERFILE -> computeDockerfileHighlighting(text);
            case TERRAFORM -> computeTerraformHighlighting(text);
            case PUPPET -> computePuppetHighlighting(text);
            case CFENGINE3 -> computeCfengineHighlighting(text);
            default -> computePlainTextHighlighting(text);
        };
    }
    
    private StyleSpans<String> computeXmlHighlighting(String text) {
        Pattern XML_TAG = Pattern.compile("(?<ELEMENT>(</?\\h*)(\\w+)([^<>]*)(\\h*/?>))" +
            "|(?<COMMENT><!--[^<>]+-->)");
        
        Matcher matcher = XML_TAG.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            spansBuilder.add(getPlainTextStyle(), matcher.start() - lastKwEnd);
            if (matcher.group("ELEMENT") != null) {
                spansBuilder.add(STYLE_XML_ELEMENT, matcher.end() - matcher.start());
            } else if (matcher.group("COMMENT") != null) {
                spansBuilder.add(STYLE_COMMENT, matcher.end() - matcher.start());
            }
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(getPlainTextStyle(), text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
    
    private StyleSpans<String> computeJsonHighlighting(String text) {
        Pattern JSON_PATTERN = Pattern.compile(
            "(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<NUMBER>-?\\d+\\.?\\d*)" +
            "|(?<BOOLEAN>\\b(true|false|null)\\b)" +
            "|(?<BRACE>[{}\\[\\]])" +
            "|(?<COLON>:)" +
            "|(?<COMMA>,)"
        );
        
        return applyPattern(text, JSON_PATTERN);
    }
    
    private StyleSpans<String> computeYamlHighlighting(String text) {
        Pattern YAML_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEY>^\\s*[\\w-]+(?=:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false|yes|no|on|off|null)\\b)" +
            "|(?<DELIMITER>[-:])"
        );
        
        return applyPattern(text, YAML_PATTERN);
    }
    
    private StyleSpans<String> computeTomlHighlighting(String text) {
        Pattern TOML_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<SECTION>\\[[^\\]]+\\])" +
            "|(?<KEY>^\\s*[\\w-]+(?=\\s*=))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false)\\b)"
        );
        
        return applyPattern(text, TOML_PATTERN);
    }
    
    private StyleSpans<String> computeMarkdownHighlighting(String text) {
        Pattern MD_PATTERN = Pattern.compile(
            "(?<HEADER>^#+.*$)" +
            "|(?<BOLD>\\*\\*[^*]+\\*\\*|__[^_]+__)" +
            "|(?<ITALIC>\\*[^*]+\\*|_[^_]+_)" +
            "|(?<CODE>`[^`]+`)" +
            "|(?<LINK>\\[([^\\]]+)\\]\\(([^)]+)\\))" +
            "|(?<LIST>^\\s*[-*+]\\s)"
        );
        
        return applyPattern(text, MD_PATTERN);
    }
    
    private StyleSpans<String> computeIniHighlighting(String text) {
        Pattern INI_PATTERN = Pattern.compile(
            "(?<COMMENT>[;#].*)" +
            "|(?<SECTION>\\[[^\\]]+\\])" +
            "|(?<KEY>^\\s*[\\w.-]+(?=\\s*=))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')"
        );
        
        return applyPattern(text, INI_PATTERN);
    }
    
    private StyleSpans<String> computeJinja2Highlighting(String text) {
        Pattern JINJA_PATTERN = Pattern.compile(
            "(?<JINJA>\\{\\{[^}]+\\}\\}|\\{%[^%]+%\\})" +
            "|(?<COMMENT>\\{#[^#]+#\\})" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')"
        );
        
        return applyPattern(text, JINJA_PATTERN);
    }
    
    private StyleSpans<String> computePythonHighlighting(String text) {
        Pattern PYTHON_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<DECORATOR>@\\w+)" +
            "|(?<STRING>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b)" +
            "|(?<BUILTIN>\\b(?:True|False|None|print|len|range|int|str|float|list|dict|set|tuple|type|isinstance|open|super|self|cls)\\b)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<FUNCTION>\\b\\w+(?=\\())",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, PYTHON_PATTERN);
    }
    
    private StyleSpans<String> computePerlHighlighting(String text) {
        Pattern PERL_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:my|our|local|sub|use|require|package|if|elsif|else|unless|while|until|for|foreach|do|next|last|return|die|warn|print|say|chomp|chop|push|pop|shift|unshift|splice|keys|values|exists|delete|defined|undef|BEGIN|END)\\b)" +
            "|(?<VARIABLE>\\$\\w+|@\\w+|%\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<FUNCTION>\\b\\w+(?=\\())",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, PERL_PATTERN);
    }
    
    private StyleSpans<String> computeRubyHighlighting(String text) {
        Pattern RUBY_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:def|class|module|end|if|elsif|else|unless|while|until|for|do|begin|rescue|ensure|raise|return|yield|block_given|require|require_relative|include|extend|attr_accessor|attr_reader|attr_writer|puts|print|nil|true|false|self|super|then|case|when|break|next|redo|retry)\\b)" +
            "|(?<VARIABLE>@{1,2}\\w+|\\$\\w+)" +
            "|(?<DECORATOR>:\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<FUNCTION>\\b\\w+(?=[!?]?\\())",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, RUBY_PATTERN);
    }
    
    private StyleSpans<String> computeShellHighlighting(String text) {
        Pattern SHELL_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'[^']*'|`[^`]*`)" +
            "|(?<KEYWORD>\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function|return|exit|break|continue|local|export|source|alias|unalias|set|unset|shift|trap|eval|exec|readonly|declare|typeset|select)\\b)" +
            "|(?<BUILTIN>\\b(?:echo|printf|read|cd|pwd|ls|cat|grep|sed|awk|find|sort|uniq|wc|head|tail|cut|tr|tee|xargs|test|mkdir|rmdir|rm|cp|mv|chmod|chown|touch|ln|basename|dirname)\\b)" +
            "|(?<VARIABLE>\\$\\{?[\\w@#?!*-]+\\}?)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, SHELL_PATTERN);
    }
    
    private StyleSpans<String> computeHtmlHighlighting(String text) {
        Pattern HTML_PATTERN = Pattern.compile(
            "(?<COMMENT><!--[\\s\\S]*?-->)" +
            "|(?<ELEMENT></?\\w+[^>]*>)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')"
        );
        
        Matcher matcher = HTML_PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spansBuilder.add(getPlainTextStyle(), matcher.start() - lastKwEnd);
            if (matcher.group("COMMENT") != null) {
                spansBuilder.add(STYLE_COMMENT, matcher.end() - matcher.start());
            } else if (matcher.group("ELEMENT") != null) {
                spansBuilder.add(STYLE_XML_ELEMENT, matcher.end() - matcher.start());
            } else if (matcher.group("STRING") != null) {
                spansBuilder.add(STYLE_STRING, matcher.end() - matcher.start());
            }
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(getPlainTextStyle(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
    
    private StyleSpans<String> computeCssHighlighting(String text) {
        Pattern CSS_PATTERN = Pattern.compile(
            "(?<COMMENT>/\\*[\\s\\S]*?\\*/)" +
            "|(?<SELECTOR>[.#]?[\\w-]+(?=\\s*\\{))" +
            "|(?<KEY>[\\w-]+(?=\\s*:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>-?\\d+\\.?\\d*(?:px|em|rem|%|vh|vw|pt|cm|mm|in|ex|ch)?)" +
            "|(?<BRACE>[{}])"
        );
        return applyCodePattern(text, CSS_PATTERN);
    }
    
    private StyleSpans<String> computeGenericCodeHighlighting(String text) {
        Pattern CODE_PATTERN = Pattern.compile(
            "(?<COMMENT>//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`[^`]*`)" +
            "|(?<KEYWORD>\\b(?:abstract|break|case|catch|class|const|continue|default|do|else|enum|export|extends|final|finally|for|func|function|goto|if|implements|import|in|instanceof|interface|let|match|module|new|package|private|protected|public|return|static|struct|switch|throw|throws|trait|try|type|typeof|var|void|volatile|while|yield|async|await|fn|impl|mod|mut|pub|ref|self|super|use|where|unsafe|loop|move|crate|dyn|extern|macro)\\b)" +
            "|(?<BUILTIN>\\b(?:true|false|null|nil|undefined|this|None|True|False|println|fmt|String|Vec|Ok|Err|Some|None)\\b)" +
            "|(?<DECORATOR>@\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*[fFdDlL]?\\b)" +
            "|(?<BRACE>[{}\\[\\]()])" +
            "|(?<FUNCTION>\\b\\w+(?=\\())",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, CODE_PATTERN);
    }
    
    private StyleSpans<String> computeSqlHighlighting(String text) {
        Pattern SQL_PATTERN = Pattern.compile(
            "(?<COMMENT>--.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:SELECT|FROM|WHERE|AND|OR|NOT|IN|IS|NULL|AS|ON|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|CROSS|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|INDEX|VIEW|TRIGGER|PROCEDURE|FUNCTION|BEGIN|END|IF|ELSE|THEN|WHEN|CASE|UNION|ALL|DISTINCT|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|EXISTS|BETWEEN|LIKE|GRANT|REVOKE|COMMIT|ROLLBACK|SAVEPOINT|WITH|RECURSIVE|RETURNING|CASCADE|CONSTRAINT|PRIMARY|KEY|FOREIGN|REFERENCES|UNIQUE|CHECK|DEFAULT|NOT|AUTO_INCREMENT|SERIAL|BOOLEAN|INTEGER|VARCHAR|TEXT|DATE|TIMESTAMP|FLOAT|DOUBLE|DECIMAL|CHAR|BLOB|BIGINT|SMALLINT)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );
        
        Matcher matcher = SQL_PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spansBuilder.add(getPlainTextStyle(), matcher.start() - lastKwEnd);
            if (matcher.group("COMMENT") != null) {
                spansBuilder.add(STYLE_COMMENT, matcher.end() - matcher.start());
            } else if (matcher.group("STRING") != null) {
                spansBuilder.add(STYLE_STRING, matcher.end() - matcher.start());
            } else if (matcher.group("KEYWORD") != null) {
                spansBuilder.add(STYLE_KEYWORD, matcher.end() - matcher.start());
            }
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(getPlainTextStyle(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
    
    private StyleSpans<String> computeDockerfileHighlighting(String text) {
        Pattern DOCKER_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEYWORD>^\\s*(?:FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<VARIABLE>\\$\\{?[\\w]+\\}?)",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, DOCKER_PATTERN);
    }
    
    private StyleSpans<String> computeTerraformHighlighting(String text) {
        Pattern TF_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*|//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<KEYWORD>\\b(?:resource|data|variable|output|locals|module|provider|terraform|backend|required_providers|required_version|for_each|count|depends_on|lifecycle|provisioner|connection|dynamic|for|in|if|else|endif)\\b)" +
            "|(?<BUILTIN>\\b(?:true|false|null|string|number|bool|list|map|set|object|tuple|any)\\b)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<SECTION>[\\w-]+\\s*\\{)" +
            "|(?<VARIABLE>var\\.[\\w]+|local\\.[\\w]+|data\\.[\\w.]+|module\\.[\\w.]+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BRACE>[{}\\[\\]])",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, TF_PATTERN);
    }
    
    /**
     * Enhanced pattern application for code languages.
     * Checks for more specific named groups used by language-specific patterns.
     */
    private StyleSpans<String> applyCodePattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String inlineStyle = getPlainTextStyle();
            
            try { if (matcher.group("COMMENT") != null) inlineStyle = STYLE_COMMENT; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("STRING") != null) inlineStyle = STYLE_STRING; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("KEYWORD") != null) inlineStyle = STYLE_KEYWORD; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("BUILTIN") != null) inlineStyle = STYLE_BUILTIN; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("DECORATOR") != null) inlineStyle = STYLE_DECORATOR; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("VARIABLE") != null) inlineStyle = STYLE_VARIABLE; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("FUNCTION") != null) inlineStyle = STYLE_FUNCTION; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("NUMBER") != null) inlineStyle = STYLE_NUMBER; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("BRACE") != null) inlineStyle = STYLE_BRACE; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("SECTION") != null) inlineStyle = STYLE_SECTION; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("KEY") != null) inlineStyle = STYLE_KEY; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("SELECTOR") != null) inlineStyle = STYLE_KEYWORD; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("ELEMENT") != null) inlineStyle = STYLE_XML_ELEMENT; } catch (IllegalArgumentException e) {}
            
            spansBuilder.add(getPlainTextStyle(), matcher.start() - lastKwEnd);
            spansBuilder.add(inlineStyle, matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(getPlainTextStyle(), text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
    
    private StyleSpans<String> computePuppetHighlighting(String text) {
        Pattern PUPPET_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:class|define|node|site|include|require|contain|inherit|import|if|elsif|else|unless|case|selector|and|or|not|in|true|false|undef|default|ensure|present|absent|running|stopped|installed|latest|purged|file|directory|link)\\b)" +
            "|(?<BUILTIN>\\b(?:package|service|file|exec|cron|user|group|mount|notify|augeas|yumrepo|apt|template|hiera|lookup|each|map|filter|reduce|notice|warning|err|fail|info|debug|alert|emerg|crit)\\b)" +
            "|(?<VARIABLE>\\$[a-zA-Z_][a-zA-Z0-9_:]*)" +
            "|(?<DECORATOR>\\b[A-Z][a-zA-Z0-9_]*(?:\\[))" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BRACE>[{}\\[\\]])" +
            "|(?<OPERATOR>=>|->|~>|\\+>|<\\||\\|>)",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, PUPPET_PATTERN);
    }
    
    private StyleSpans<String> computeCfengineHighlighting(String text) {
        Pattern CFE_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:bundle|body|promise|agent|common|server|monitor|knowledge|edit_line|edit_xml|delete_lines|insert_lines|field_edits|replace_patterns|classes|commands|databases|files|interfaces|methods|packages|processes|reports|services|storage|vars|meta|defaults)\\b)" +
            "|(?<BUILTIN>\\b(?:string|int|real|slist|ilist|rlist|data|classmatch|regcmp|isvariable|fileexists|isdir|islink|isplain|returnszero|usemodule|canonify|translatepath|lastnode|dirname|join|length|nth|sort|unique|filter|maplist|maparray|some|none|every|reglist|getindices|getvalues|mergedata|readstringlist|readintlist|execresult|readfile|readjson|readyaml|parsejson|parseyaml|storejson|format|string_mustache|bundlestate|classesmatching|variablesmatching|getclassmetatags|getvariablemetatags|now|ago|accumulated|on|hash|escape)\\b)" +
            "|(?<VARIABLE>\\$\\([^)]+\\)|\\$\\{[^}]+\\}|@\\([^)]+\\)|@\\{[^}]+\\})" +
            "|(?<SECTION>[a-zA-Z_]+:)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<OPERATOR>=>|->)",
            Pattern.MULTILINE
        );
        return applyCodePattern(text, CFE_PATTERN);
    }
    
    private StyleSpans<String> computePlainTextHighlighting(String text) {
        return StyleSpans.singleton(getPlainTextStyle(), text.length());
    }
    
    private StyleSpans<String> applyPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String inlineStyle = getPlainTextStyle();
            
            // Check each group safely and map to inline CSS
            try { if (matcher.group("COMMENT") != null) inlineStyle = STYLE_COMMENT; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("STRING") != null) inlineStyle = STYLE_STRING; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("NUMBER") != null) inlineStyle = STYLE_NUMBER; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("BOOLEAN") != null) inlineStyle = STYLE_BOOLEAN; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("KEY") != null) inlineStyle = STYLE_KEY; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("SECTION") != null) inlineStyle = STYLE_SECTION; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("HEADER") != null) inlineStyle = STYLE_HEADER; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("BOLD") != null) inlineStyle = STYLE_BOLD; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("ITALIC") != null) inlineStyle = STYLE_ITALIC; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("CODE") != null) inlineStyle = STYLE_CODE; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("LINK") != null) inlineStyle = STYLE_LINK; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("LIST") != null) inlineStyle = STYLE_LIST; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("JINJA") != null) inlineStyle = STYLE_JINJA; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("BRACE") != null) inlineStyle = STYLE_BRACE; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("COLON") != null) inlineStyle = STYLE_PUNCTUATION; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("COMMA") != null) inlineStyle = STYLE_PUNCTUATION; } catch (IllegalArgumentException e) {}
            try { if (inlineStyle.equals(getPlainTextStyle()) && matcher.group("DELIMITER") != null) inlineStyle = STYLE_PUNCTUATION; } catch (IllegalArgumentException e) {}
            
            spansBuilder.add(getPlainTextStyle(), matcher.start() - lastKwEnd);
            spansBuilder.add(inlineStyle, matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(getPlainTextStyle(), text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    private void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    public boolean isModified() {
        return isModified;
    }
    
    public String getFilePath() {
        return isRemoteFile ? remotePath : localPath.toString();
    }
    
    public boolean isRemote() {
        return isRemoteFile;
    }
    
    /**
     * Creates a SessionState for saving this editor tab.
     */
    public SessionState createSessionState() {
        SessionState state = new SessionState();
        state.setTabType(SessionState.TabType.FILE_EDITOR);
        state.setEditorFilePath(isRemoteFile ? remotePath : localPath.toString());
        state.setEditorIsRemote(isRemoteFile);
        state.setEditorFileType(fileType.name());
        state.setTabTitle(getText());
        
        if (isRemoteFile && sftpSession != null) {
            // Store connection info
            state.setConnectionId(null); // Will be set by caller if needed
        }
        
        return state;
    }
    
    /**
     * Sets the connection ID for this editor tab (used during project save).
     */
    public void setConnectionIdForState(SessionState state, String connectionId) {
        if (state != null && isRemoteFile) {
            state.setConnectionId(connectionId);
        }
    }
    
    public SFTPSession getSftpSession() {
        return sftpSession;
    }
    
    /**
     * Inserts text at the current cursor position in the code editor.
     * Used by snippet management to insert code snippets.
     */
    public void insertTextAtCursor(String text) {
        if (text == null || text.isEmpty()) return;
        int caretPos = codeArea.getCaretPosition();
        codeArea.insertText(caretPos, text);
    }
}

