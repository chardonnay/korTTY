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
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
    
    private final CodeArea codeArea;
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
        ANSIBLE_YAML
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
        
        // Create code area
        codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.setStyle("-fx-font-family: 'Monaco', 'Menlo', 'Consolas', monospace; -fx-font-size: 12px;");
        
        // Load syntax highlighting CSS
        try {
            codeArea.getStylesheets().add(getClass().getResource("/styles/editor.css").toExternalForm());
        } catch (Exception e) {
            logger.warn("Failed to load editor CSS", e);
        }
        
        // Load content
        String text = new String(content, StandardCharsets.UTF_8);
        codeArea.replaceText(0, 0, text);
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
        
        // Status bar
        statusLabel = new Label(I18n.get("editor.status.ready"));
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane rootContent = createContent();
        setContent(rootContent);
        
        // Keyboard shortcuts
        setupKeyboardShortcuts();
        
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
        
        // Create code area
        codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.setStyle("-fx-font-family: 'Monaco', 'Menlo', 'Consolas', monospace; -fx-font-size: 12px;");
        
        // Load syntax highlighting CSS
        try {
            codeArea.getStylesheets().add(getClass().getResource("/styles/editor.css").toExternalForm());
        } catch (Exception e) {
            logger.warn("Failed to load editor CSS", e);
        }
        
        // Load content
        String text = Files.readString(localPath, StandardCharsets.UTF_8);
        codeArea.replaceText(0, 0, text);
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
        
        // Status bar
        statusLabel = new Label(I18n.get("editor.status.ready"));
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane rootContent = createContent();
        setContent(rootContent);
        
        // Keyboard shortcuts
        setupKeyboardShortcuts();
        
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
        
        // Status bar
        HBox statusBar = new HBox(statusLabel);
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        root.setBottom(statusBar);
        
        return root;
    }
    
    private ToolBar createToolBar() {
        Button saveBtn = new Button(I18n.get("editor.save"));
        saveBtn.setOnAction(e -> save());
        
        Button saveAsBtn = new Button(I18n.get("editor.saveAs"));
        saveAsBtn.setOnAction(e -> saveAs());
        
        Button findBtn = new Button(I18n.get("editor.find"));
        findBtn.setOnAction(e -> toggleSearchPanel());
        
        Button replaceBtn = new Button(I18n.get("editor.replace"));
        replaceBtn.setOnAction(e -> toggleSearchPanel());
        
        Button lintBtn = new Button(I18n.get("editor.lint"));
        lintBtn.setOnAction(e -> runLinter());
        
        return new ToolBar(saveBtn, saveAsBtn, new Separator(), findBtn, replaceBtn, new Separator(), lintBtn);
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
        
        Button closeBtn = new Button(I18n.get("editor.search.close"));
        closeBtn.setOnAction(e -> toggleSearchPanel());
        
        HBox optionsRow = new HBox(10, regexCheckBox, caseSensitiveCheckBox, closeBtn);
        optionsRow.setStyle("-fx-alignment: center-left;");
        
        // Search on text change
        searchField.textProperty().addListener((obs, oldVal, newVal) -> performSearch());
        regexCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> performSearch());
        caseSensitiveCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> performSearch());
        
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
            }
        });
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
    
    private void performSearch() {
        String searchText = searchField.getText();
        if (searchText == null || searchText.isEmpty()) {
            searchMatches.clear();
            searchResultLabel.setText("");
            return;
        }
        
        searchMatches.clear();
        String content = codeArea.getText();
        
        try {
            if (regexCheckBox.isSelected()) {
                int flags = caseSensitiveCheckBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(searchText, flags);
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    searchMatches.add(matcher.start());
                }
            } else {
                String searchLower = caseSensitiveCheckBox.isSelected() ? searchText : searchText.toLowerCase();
                String contentLower = caseSensitiveCheckBox.isSelected() ? content : content.toLowerCase();
                int index = 0;
                while ((index = contentLower.indexOf(searchLower, index)) != -1) {
                    searchMatches.add(index);
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
            performSearch();
            return;
        }
        
        currentSearchIndex = (currentSearchIndex + 1) % searchMatches.size();
        highlightMatch(currentSearchIndex);
        searchResultLabel.setText(String.format("%d/%d", currentSearchIndex + 1, searchMatches.size()));
    }
    
    private void findPrevious() {
        if (searchMatches.isEmpty()) {
            performSearch();
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
            performSearch();
            return;
        }
        
        int start = searchMatches.get(currentSearchIndex);
        String searchText = searchField.getText();
        String replaceText = replaceField.getText();
        
        codeArea.replaceText(start, start + searchText.length(), replaceText);
        
        // Re-search after replacement
        performSearch();
    }
    
    private void replaceAll() {
        String replaceText = replaceField.getText();
        if (searchMatches.isEmpty()) {
            performSearch();
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
        performSearch();
    }
    
    private void save() {
        try {
            String content = codeArea.getText();
            
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
            case YAML, ANSIBLE_YAML -> checkCommandAvailable("yamllint") ? Optional.of("yamllint") : Optional.empty();
            case JSON -> checkCommandAvailable("jsonlint") ? Optional.of("jsonlint") : Optional.empty();
            case XML -> checkCommandAvailable("xmllint") ? Optional.of("xmllint") : Optional.empty();
            case MARKDOWN -> checkCommandAvailable("markdownlint") ? Optional.of("markdownlint") : Optional.empty();
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
            // Check if it's an Ansible playbook
            if (lower.contains("playbook") || lower.contains("ansible")) {
                return FileType.ANSIBLE_YAML;
            }
            return FileType.YAML;
        }
        if (lower.endsWith(".toml")) return FileType.TOML;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return FileType.MARKDOWN;
        if (lower.endsWith(".ini")) return FileType.INI;
        if (lower.endsWith(".cfg") || lower.endsWith(".conf")) return FileType.CFG;
        if (lower.endsWith(".j2") || lower.endsWith(".jinja") || lower.endsWith(".jinja2")) return FileType.JINJA2;
        
        return FileType.PLAIN_TEXT;
    }
    
    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        return switch (fileType) {
            case XML -> computeXmlHighlighting(text);
            case JSON -> computeJsonHighlighting(text);
            case YAML, ANSIBLE_YAML -> computeYamlHighlighting(text);
            case TOML -> computeTomlHighlighting(text);
            case MARKDOWN -> computeMarkdownHighlighting(text);
            case INI, CFG -> computeIniHighlighting(text);
            case JINJA2 -> computeJinja2Highlighting(text);
            default -> computePlainTextHighlighting(text);
        };
    }
    
    private StyleSpans<Collection<String>> computeXmlHighlighting(String text) {
        Pattern XML_TAG = Pattern.compile("(?<ELEMENT>(</?\\h*)(\\w+)([^<>]*)(\\h*/?>))" +
            "|(?<COMMENT><!--[^<>]+-->)");
        
        Matcher matcher = XML_TAG.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            if (matcher.group("ELEMENT") != null) {
                spansBuilder.add(Collections.singleton("xml-element"), matcher.end() - matcher.start());
            } else if (matcher.group("COMMENT") != null) {
                spansBuilder.add(Collections.singleton("comment"), matcher.end() - matcher.start());
            }
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
    
    private StyleSpans<Collection<String>> computeJsonHighlighting(String text) {
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
    
    private StyleSpans<Collection<String>> computeYamlHighlighting(String text) {
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
    
    private StyleSpans<Collection<String>> computeTomlHighlighting(String text) {
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
    
    private StyleSpans<Collection<String>> computeMarkdownHighlighting(String text) {
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
    
    private StyleSpans<Collection<String>> computeIniHighlighting(String text) {
        Pattern INI_PATTERN = Pattern.compile(
            "(?<COMMENT>[;#].*)" +
            "|(?<SECTION>\\[[^\\]]+\\])" +
            "|(?<KEY>^\\s*[\\w.-]+(?=\\s*=))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')"
        );
        
        return applyPattern(text, INI_PATTERN);
    }
    
    private StyleSpans<Collection<String>> computeJinja2Highlighting(String text) {
        Pattern JINJA_PATTERN = Pattern.compile(
            "(?<JINJA>\\{\\{[^}]+\\}\\}|\\{%[^%]+%\\})" +
            "|(?<COMMENT>\\{#[^#]+#\\})" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')"
        );
        
        return applyPattern(text, JINJA_PATTERN);
    }
    
    private StyleSpans<Collection<String>> computePlainTextHighlighting(String text) {
        return StyleSpans.singleton(Collections.emptyList(), text.length());
    }
    
    private StyleSpans<Collection<String>> applyPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String styleClass = null;
            
            if (matcher.group("COMMENT") != null) styleClass = "comment";
            else if (matcher.group("STRING") != null) styleClass = "string";
            else if (matcher.group("NUMBER") != null) styleClass = "number";
            else if (matcher.group("BOOLEAN") != null) styleClass = "boolean";
            else if (matcher.group("KEY") != null) styleClass = "key";
            else if (matcher.group("SECTION") != null) styleClass = "section";
            else if (matcher.group("HEADER") != null) styleClass = "header";
            else if (matcher.group("BOLD") != null) styleClass = "bold";
            else if (matcher.group("ITALIC") != null) styleClass = "italic";
            else if (matcher.group("CODE") != null) styleClass = "code";
            else if (matcher.group("LINK") != null) styleClass = "link";
            else if (matcher.group("LIST") != null) styleClass = "list";
            else if (matcher.group("JINJA") != null) styleClass = "jinja";
            else if (matcher.group("BRACE") != null) styleClass = "brace";
            else if (matcher.group("COLON") != null) styleClass = "colon";
            else if (matcher.group("COMMA") != null) styleClass = "comma";
            else if (matcher.group("DELIMITER") != null) styleClass = "delimiter";
            
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass != null ? styleClass : "plain"), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        
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
}

