package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetCodeFormatter;
import de.kortty.core.SnippetLinter;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialog for creating or editing a code snippet.
 * Provides form fields for name, language, category, tags, and
 * a syntax-highlighted content editor with placeholder help.
 */
public class SnippetEditDialog extends ThemeAwareDialog<Snippet> {
    
    private final TextField nameField;
    private final ComboBox<String> languageCombo;
    private final ComboBox<String> categoryCombo;
    private final TextField tagsField;
    private final InlineCssTextArea contentArea;
    private final CheckBox wordWrapCheckBox;
    private final Button formatBtn;
    private final Button lintBtn;
    private final Label statusLabel;
    private final Snippet existingSnippet;
    private final EditorSettingsHelper.Settings editorSettings;
    
    // Syntax highlight style constants (reused from FileEditorTab)
    private static final String STYLE_COMMENT = "-fx-fill: #888888; -fx-font-style: italic;";
    private static final String STYLE_STRING = "-fx-fill: #008800;";
    private static final String STYLE_NUMBER = "-fx-fill: #0066cc;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #cc00cc; -fx-font-weight: bold;";
    private static final String STYLE_KEY = "-fx-fill: #cc0000; -fx-font-weight: bold;";
    private static final String STYLE_KEYWORD = "-fx-fill: #0000cc; -fx-font-weight: bold;";
    private static final String STYLE_SECTION = "-fx-fill: #9900cc; -fx-font-weight: bold;";
    private static final String STYLE_VARIABLE = "-fx-fill: #cc6600;";
    private static final String STYLE_BRACE = "-fx-fill: #cc6600; -fx-font-weight: bold;";
    private static final String STYLE_PLAIN = "-fx-fill: #d4d4d4;";
    
    private static final List<String> LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "dockerfile"
    );
    
    /**
     * Creates a new snippet edit dialog.
     *
     * @param snippet            the snippet to edit, or null for creating a new one
     * @param existingCategories list of existing category names
     */
    public SnippetEditDialog(Snippet snippet, List<String> existingCategories) {
        this.existingSnippet = snippet;
        EditorSettingsHelper.Settings loaded = EditorSettingsHelper.loadSnippetSettings();
        // Force a visible block caret in Snippet Editor (user request).
        this.editorSettings = new EditorSettingsHelper.Settings(
                loaded.fontFamily(),
                loaded.fontSize(),
                loaded.foregroundColor(),
                loaded.backgroundColor(),
                "BLOCK",
                loaded.cursorColor()
        );
        
        setTitle(snippet == null ? I18n.get("snippets.addTitle") : I18n.get("snippets.editTitle"));
        setResizable(true);
        
        // Form fields
        nameField = new TextField();
        nameField.setPromptText(I18n.get("snippets.name"));
        nameField.setPrefWidth(400);
        
        languageCombo = new ComboBox<>();
        languageCombo.getItems().addAll(LANGUAGES);
        languageCombo.setValue("plain");
        languageCombo.setPrefWidth(200);
        
        categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        if (existingCategories != null) {
            categoryCombo.getItems().addAll(existingCategories);
        }
        categoryCombo.setPromptText(I18n.get("snippets.categoryNew"));
        categoryCombo.setPrefWidth(200);
        
        tagsField = new TextField();
        tagsField.setPromptText(I18n.get("snippets.tagsPrompt"));
        tagsField.setPrefWidth(400);
        
        // Content area with syntax highlighting – use saved editor settings
        contentArea = new InlineCssTextArea();
        contentArea.setPrefHeight(350);
        contentArea.setPrefWidth(600);
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(contentArea, editorSettings);
        // Ensure block caret remains visible while typing (caret node may be recreated).
        contentArea.caretPositionProperty().addListener((obs, oldPos, newPos) ->
                EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
        contentArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.UP || code == KeyCode.DOWN || code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.HOME || code == KeyCode.END
                    || code == KeyCode.PAGE_UP || code == KeyCode.PAGE_DOWN) {
                refreshBlockCaretSoon();
            }
        });
        
        // Wrap content area in VirtualizedScrollPane for scrollbars
        var contentScrollPane = EditorSettingsHelper.createScrollPane(contentArea);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);
        
        // Word wrap checkbox – persistent setting
        wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
        boolean savedWordWrap = loadWordWrapSetting();
        wordWrapCheckBox.setSelected(savedWordWrap);
        contentArea.setWrapText(savedWordWrap);
        
        wordWrapCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            contentArea.setWrapText(newVal);
            saveWordWrapSetting(newVal);
        });
        
        // Right-click context menu on content area
        contentArea.setContextMenu(createEditorContextMenu());
        
        // Re-apply highlighting when language changes
        languageCombo.setOnAction(e -> {
            applyHighlighting();
            updateFormatLintButtonState();
        });
        
        // Re-apply highlighting on text change
        contentArea.textProperty().addListener((obs, oldText, newText) -> {
            applyHighlighting();
            EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
        });
        
        // Placeholder info label
        Label placeholderInfo = new Label(I18n.get("snippets.placeholderInfo"));
        placeholderInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        placeholderInfo.setWrapText(true);
        
        // Layout
        GridPane formGrid = new GridPane();
        formGrid.setHgap(10);
        formGrid.setVgap(8);
        formGrid.setPadding(new Insets(10));
        
        formGrid.add(new Label(I18n.get("snippets.name") + ":"), 0, 0);
        formGrid.add(nameField, 1, 0);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        
        HBox langCatBox = new HBox(10);
        langCatBox.getChildren().addAll(
            new Label(I18n.get("snippets.language") + ":"), languageCombo,
            new Label(I18n.get("snippets.category") + ":"), categoryCombo
        );
        formGrid.add(langCatBox, 0, 1, 2, 1);
        
        formGrid.add(new Label(I18n.get("snippets.tags") + ":"), 0, 2);
        formGrid.add(tagsField, 1, 2);
        GridPane.setHgrow(tagsField, Priority.ALWAYS);
        
        // Content header: label + Format / Lint buttons (with symbols) + Word wrap
        formatBtn = new Button("\u2728 " + I18n.get("editor.format"));
        formatBtn.setTooltip(new Tooltip(I18n.get("editor.format.tooltip", I18n.get("editor.format.tooltip.builtin"))));
        formatBtn.setOnAction(e -> runFormat());
        
        lintBtn = new Button("\u2713 " + I18n.get("editor.lint"));
        lintBtn.setTooltip(new Tooltip(I18n.get("editor.lint.title")));
        lintBtn.setOnAction(e -> runLint());
        
        updateFormatLintButtonState();
        
        HBox contentHeader = new HBox(10,
                new Label(I18n.get("snippets.content") + ":"),
                formatBtn, lintBtn, new Separator(), wordWrapCheckBox);
        contentHeader.setAlignment(Pos.CENTER_LEFT);
        formGrid.add(contentHeader, 0, 3, 2, 1);
        
        VBox contentBox = new VBox(5, contentScrollPane, placeholderInfo);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);
        formGrid.add(contentBox, 0, 4, 2, 1);
        GridPane.setVgrow(contentBox, Priority.ALWAYS);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #6c757d;");
        statusLabel.setMinHeight(18);
        statusLabel.setText("");

        VBox rootLayout = new VBox(0, formGrid, statusLabel);
        VBox.setVgrow(formGrid, Priority.ALWAYS);

        getDialogPane().setContent(rootLayout);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(700);
        getDialogPane().setPrefHeight(550);
        
        // Disable OK if name or content is empty
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        nameField.textProperty().addListener((obs, o, n) -> validateForm(okButton));
        contentArea.textProperty().addListener((obs, o, n) -> validateForm(okButton));
        
        // Pre-fill if editing
        if (snippet != null) {
            nameField.setText(snippet.getName());
            languageCombo.setValue(snippet.getLanguage() != null ? snippet.getLanguage() : "plain");
            categoryCombo.setValue(snippet.getCategory());
            tagsField.setText(snippet.getTagsAsString());
            contentArea.replaceText(snippet.getContent() != null ? snippet.getContent() : "");
            applyHighlighting();
        }
        updateFormatLintButtonState();
        
        // Restore saved geometry
        restoreGeometry();
        
        // Result converter (also saves geometry)
        setResultConverter(buttonType -> {
            saveGeometry();
            if (buttonType == ButtonType.OK) {
                Snippet result = existingSnippet != null ? existingSnippet : new Snippet();
                result.setName(nameField.getText().trim());
                result.setContent(contentArea.getText());
                result.setLanguage(languageCombo.getValue());
                result.setCategory(categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : null);
                result.setTagsFromString(tagsField.getText());
                return result;
            }
            return null;
        });
    }
    
    private void restoreGeometry() {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            var geo = gs.getSnippetEditGeometry();
            if (geo != null && geo.getWidth() > 0 && geo.getHeight() > 0) {
                getDialogPane().setPrefWidth(geo.getWidth());
                getDialogPane().setPrefHeight(geo.getHeight());
                setOnShowing(event -> {
                    javafx.stage.Window window = getDialogPane().getScene().getWindow();
                    if (window instanceof javafx.stage.Stage stage) {
                        stage.setX(geo.getX());
                        stage.setY(geo.getY());
                        stage.setWidth(geo.getWidth());
                        stage.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception e) {
            // Ignore - use defaults
        }
    }
    
    private void saveGeometry() {
        try {
            javafx.stage.Window window = getDialogPane().getScene().getWindow();
            if (window instanceof javafx.stage.Stage stage) {
                var geo = new WindowGeometry(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                gs.setSnippetEditGeometry(geo);
                KorTTYApplication.getInstance().getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private boolean loadWordWrapSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetWordWrap();
        } catch (Exception e) {
            return true; // default on
        }
    }
    
    private void saveWordWrapSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetWordWrap(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private void validateForm(Button okButton) {
        boolean valid = nameField.getText() != null && !nameField.getText().isBlank()
                && contentArea.getText() != null && !contentArea.getText().isBlank();
        okButton.setDisable(!valid);
    }

    private ContextMenu createEditorContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem cutItem = new MenuItem(I18n.get("editor.context.cut"));
        cutItem.setOnAction(e -> contentArea.cut());
        MenuItem copyItem = new MenuItem(I18n.get("editor.context.copy"));
        copyItem.setOnAction(e -> contentArea.copy());
        MenuItem pasteItem = new MenuItem(I18n.get("editor.context.paste"));
        pasteItem.setOnAction(e -> contentArea.paste());
        MenuItem deleteItem = new MenuItem(I18n.get("editor.context.delete"));
        deleteItem.setOnAction(e -> contentArea.replaceSelection(""));
        MenuItem selectAllItem = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAllItem.setOnAction(e -> contentArea.selectAll());
        MenuItem formatItem = new MenuItem(I18n.get("editor.format"));
        formatItem.setOnAction(e -> runFormat());
        MenuItem lintItem = new MenuItem(I18n.get("editor.lint"));
        lintItem.setOnAction(e -> runLint());
        CheckMenuItem wordWrapItem = new CheckMenuItem(I18n.get("snippets.wordWrap"));
        wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
        wordWrapItem.setOnAction(e -> {
            boolean on = wordWrapItem.isSelected();
            wordWrapCheckBox.setSelected(on);
            contentArea.setWrapText(on);
            saveWordWrapSetting(on);
        });
        menu.getItems().addAll(
                cutItem, copyItem, pasteItem, deleteItem,
                new SeparatorMenuItem(),
                selectAllItem,
                new SeparatorMenuItem(),
                formatItem, lintItem,
                new SeparatorMenuItem(),
                wordWrapItem
        );
        menu.setOnShowing(e -> {
            boolean hasSelection = contentArea.getSelection().getLength() > 0;
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
            String lang = languageCombo.getValue();
            formatItem.setDisable(!SnippetCodeFormatter.isSupported(lang));
            lintItem.setDisable(!SnippetLinter.isSupported(lang));
        });
        return menu;
    }

    private void updateFormatLintButtonState() {
        String lang = languageCombo.getValue();
        formatBtn.setDisable(!SnippetCodeFormatter.isSupported(lang));
        lintBtn.setDisable(!SnippetLinter.isSupported(lang));
    }

    private void runFormat() {
        String lang = languageCombo.getValue();
        if (!SnippetCodeFormatter.isSupported(lang)) {
            setStatus(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"));
            return;
        }
        int start = contentArea.getSelection().getStart();
        int end = contentArea.getSelection().getEnd();
        String text;
        boolean selectionOnly = (end > start);
        if (selectionOnly) {
            text = contentArea.getSelectedText();
            if (text == null || text.isBlank()) return;
        } else {
            text = contentArea.getText();
            if (text == null || text.isBlank()) return;
        }
        String formatted = SnippetCodeFormatter.format(text, lang);
        if (formatted == null) {
            setStatus(I18n.get("editor.format.failed"));
            return;
        }
        if (formatted.equals(text)) {
            setStatus(I18n.get("editor.format.noChanges"));
            return;
        }
        if (selectionOnly) {
            contentArea.replaceText(start, end, formatted);
        } else {
            contentArea.replaceText(formatted);
        }
        applyHighlighting();
        setStatus(I18n.get("editor.format.success"));
    }

    private void runLint() {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        if (!SnippetLinter.isSupported(lang)) {
            showAlert(I18n.get("editor.lint.notAvailable", lang != null ? lang : "plain"));
            return;
        }
        SnippetLinter.LintResult result = SnippetLinter.lint(text != null ? text : "", lang);
        if (result.isSuccess()) {
            showAlert(I18n.get("editor.lint.success"), Alert.AlertType.INFORMATION);
        } else {
            showAlert(I18n.get("editor.lint.errors") + "\n\n" + result.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private void showAlert(String message) {
        showAlert(message, Alert.AlertType.INFORMATION);
    }

    private void showAlert(String message, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(I18n.get("snippets.editTitle"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message != null ? message : "");
        }
    }

    private void refreshBlockCaretSoon() {
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
        // Run once more on the next pulse, because RichTextFX may recreate caret after key handling.
        Platform.runLater(() -> EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
    }
    
    // ---- Syntax Highlighting ----
    
    private void applyHighlighting() {
        try {
            String text = contentArea.getText();
            if (text == null || text.isEmpty()) return;
            
            String lang = languageCombo.getValue();
            String plainStyle = EditorSettingsHelper.getPlainTextStyle(editorSettings);
            StyleSpans<String> spans = computeHighlighting(text, lang, plainStyle);
            contentArea.setStyleSpans(0, spans);
        } catch (Exception e) {
            // Ignore highlighting errors
        }
    }
    
    static StyleSpans<String> computeHighlighting(String text, String language) {
        return computeHighlighting(text, language, STYLE_PLAIN);
    }
    
    static StyleSpans<String> computeHighlighting(String text, String language, String plainStyle) {
        if (language == null) language = "plain";
        return switch (language) {
            case "bash", "shell", "sh", "zsh" -> applyPattern(text, computeBashPattern(), plainStyle);
            case "python" -> applyPattern(text, computePythonPattern(), plainStyle);
            case "perl", "pl" -> applyPattern(text, computePerlPattern(), plainStyle);
            case "java", "groovy" -> applyPattern(text, computeJavaPattern(), plainStyle);
            case "javascript" -> applyPattern(text, computeJavaScriptPattern(), plainStyle);
            case "sql" -> applyPattern(text, computeSqlPattern(), plainStyle);
            case "xml" -> applyPattern(text, computeXmlPattern(), plainStyle);
            case "json" -> applyPattern(text, computeJsonPattern(), plainStyle);
            case "yaml" -> applyPattern(text, computeYamlPattern(), plainStyle);
            case "properties", "ini" -> applyPattern(text, computeIniPattern(), plainStyle);
            case "dockerfile" -> applyPattern(text, computeDockerfilePattern(), plainStyle);
            case "markdown" -> applyPattern(text, computeMarkdownPattern(), plainStyle);
            default -> StyleSpans.singleton(plainStyle, text.length());
        };
    }
    
    private static Pattern computeBashPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'[^']*')" +
            "|(?<VARIABLE>\\$\\{?[\\w]+}?)" +
            "|(?<KEYWORD>\\b(if|then|else|elif|fi|for|while|do|done|case|esac|function|return|local|export|source|echo|exit|cd|ls|grep|awk|sed|cat|chmod|chown|mkdir|rm|cp|mv|find|xargs|curl|wget|sudo|apt|yum|dnf|pip|npm|docker|kubectl|git|ssh|scp|rsync|tar|zip|unzip|gzip|gunzip)\\b)" +
            "|(?<NUMBER>\\b\\d+\\b)"
        );
    }
    
    private static Pattern computePythonPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(def|class|import|from|as|if|elif|else|for|while|with|try|except|finally|raise|return|yield|lambda|and|or|not|in|is|None|True|False|pass|break|continue|global|nonlocal|assert|del|print)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)" +
            "|(?<VARIABLE>\\bself\\b)"
        );
    }

    private static Pattern computePerlPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:my|our|local|sub|use|require|package|if|elsif|else|unless|while|until|for|foreach|do|next|last|return|die|warn|print|say|chomp|chop|push|pop|shift|unshift|splice|keys|values|exists|delete|defined|undef|BEGIN|END)\\b)" +
            "|(?<VARIABLE>\\$\\w+|@\\w+|%\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<FUNCTION>\\b\\w+(?=\\())",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeJavaPattern() {
        return Pattern.compile(
            "(?<COMMENT>//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<KEYWORD>\\b(public|private|protected|static|final|abstract|class|interface|extends|implements|new|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|import|package|void|int|long|double|float|boolean|char|byte|short|String|var|record|sealed|permits|yield)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*[fFdDlL]?\\b)" +
            "|(?<BOOLEAN>\\b(true|false|null)\\b)"
        );
    }
    
    private static Pattern computeJavaScriptPattern() {
        return Pattern.compile(
            "(?<COMMENT>//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`)" +
            "|(?<KEYWORD>\\b(const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|new|class|extends|import|export|from|default|async|await|yield|typeof|instanceof)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false|null|undefined|NaN)\\b)"
        );
    }
    
    private static Pattern computeSqlPattern() {
        return Pattern.compile(
            "(?i)(?<COMMENT>--.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|INDEX|VIEW|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|ON|AND|OR|NOT|IN|EXISTS|BETWEEN|LIKE|IS|NULL|AS|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|COUNT|SUM|AVG|MAX|MIN|CASE|WHEN|THEN|ELSE|END|BEGIN|COMMIT|ROLLBACK|GRANT|REVOKE|PRIMARY|KEY|FOREIGN|REFERENCES|CONSTRAINT|DEFAULT|CHECK|UNIQUE|AUTO_INCREMENT)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)"
        );
    }
    
    private static Pattern computeXmlPattern() {
        return Pattern.compile(
            "(?<COMMENT><!--[\\s\\S]*?-->)" +
            "|(?<KEYWORD></?\\w+[^>]*>)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")"
        );
    }
    
    private static Pattern computeJsonPattern() {
        return Pattern.compile(
            "(?<KEY>\"([^\"\\\\]|\\\\.)*\"\\s*(?=:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<NUMBER>-?\\d+\\.?\\d*)" +
            "|(?<BOOLEAN>\\b(true|false|null)\\b)" +
            "|(?<BRACE>[{}\\[\\]])"
        );
    }
    
    private static Pattern computeYamlPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEY>^\\s*[\\w.-]+(?=:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false|yes|no|null)\\b)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeIniPattern() {
        return Pattern.compile(
            "(?<COMMENT>[;#].*)" +
            "|(?<SECTION>\\[[^\\]]+\\])" +
            "|(?<KEY>^\\s*[\\w.-]+(?=\\s*=))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeDockerfilePattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEYWORD>^\\s*(FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<VARIABLE>\\$\\{?[\\w]+}?)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeMarkdownPattern() {
        return Pattern.compile(
            "(?<KEYWORD>^#+.*$)" +
            "|(?<STRING>\\*\\*[^*]+\\*\\*|__[^_]+__)" +
            "|(?<COMMENT>`[^`]+`)" +
            "|(?<VARIABLE>\\[([^\\]]+)\\]\\(([^)]+)\\))",
            Pattern.MULTILINE
        );
    }
    
    private static StyleSpans<String> applyPattern(String text, Pattern pattern) {
        return applyPattern(text, pattern, STYLE_PLAIN);
    }
    
    private static StyleSpans<String> applyPattern(String text, Pattern pattern, String plainStyle) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String style = plainStyle;
            try { if (matcher.group("COMMENT") != null) style = STYLE_COMMENT; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("STRING") != null) style = STYLE_STRING; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("NUMBER") != null) style = STYLE_NUMBER; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("BOOLEAN") != null) style = STYLE_BOOLEAN; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("KEY") != null) style = STYLE_KEY; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("KEYWORD") != null) style = STYLE_KEYWORD; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("SECTION") != null) style = STYLE_SECTION; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("VARIABLE") != null) style = STYLE_VARIABLE; } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(plainStyle) && matcher.group("BRACE") != null) style = STYLE_BRACE; } catch (IllegalArgumentException ignored) {}
            
            spansBuilder.add(plainStyle, matcher.start() - lastKwEnd);
            spansBuilder.add(style, matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(plainStyle, text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
}
