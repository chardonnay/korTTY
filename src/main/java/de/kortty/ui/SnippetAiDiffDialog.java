package de.kortty.ui;

import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.SnippetEditorProfile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;

/**
 * Shows the original and AI-generated replacement before applying an editor change.
 */
public class SnippetAiDiffDialog extends ThemeAwareDialog<Boolean> {

    private static final int MIN_PREVIEW_FONT_SIZE = 8;
    private static final int MAX_PREVIEW_FONT_SIZE = 72;
    private static final int PREVIEW_FONT_STEP = 1;
    private static final List<String> HIGHLIGHT_LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "dockerfile");

    private final MonacoEditorPane beforeArea;
    private final MonacoEditorPane afterArea;
    private final ComboBox<String> syntaxCombo;
    private final Label fontSizeLabel;
    private final SnippetEditorProfile editorProfile;
    private EditorSettingsHelper.Settings previewSettings;
    private MonacoEditorPane focusedPreviewArea;

    public SnippetAiDiffDialog(Window owner, String title, String summary, String originalText, String replacementText) {
        this(owner, title, summary, originalText, replacementText, null, EditorSettingsHelper.loadSnippetSettings(), null);
    }

    public SnippetAiDiffDialog(
        Window owner,
        String title,
        String summary,
        String originalText,
        String replacementText,
        String snippetLanguage,
        EditorSettingsHelper.Settings editorSettings,
        SnippetEditorProfile editorProfile) {

        this.previewSettings = editorSettings != null ? editorSettings : EditorSettingsHelper.loadSnippetSettings();
        this.editorProfile = editorProfile != null ? new SnippetEditorProfile(editorProfile) : null;

        setTitle(title != null && !title.isBlank() ? title : I18n.get("snippets.ai.diff.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        Label summaryLabel = new Label(summary != null && !summary.isBlank()
            ? summary
            : I18n.get("snippets.ai.diff.summary.empty"));
        summaryLabel.setWrapText(true);
        summaryLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        beforeArea = createPreviewArea(originalText);
        afterArea = createPreviewArea(replacementText);
        focusedPreviewArea = afterArea;

        String detectedLanguage = SnippetLanguageSupport.detectSnippetLanguage(
            snippetLanguage,
            nonBlank(replacementText) ? replacementText : originalText);
        syntaxCombo = new ComboBox<>();
        syntaxCombo.getItems().addAll(HIGHLIGHT_LANGUAGES);
        syntaxCombo.setValue(HIGHLIGHT_LANGUAGES.contains(detectedLanguage) ? detectedLanguage : "plain");
        syntaxCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyHighlighting());

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-PREVIEW_FONT_STEP));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changePreviewFontSize(PREVIEW_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();

        Button copyButton = new Button("\u29c9");
        copyButton.setTooltip(new Tooltip(I18n.get("snippets.copyClipboard")));
        copyButton.setOnAction(event -> copyFocusedPreviewText());

        Region spacer = new Region();
        HBox toolbar = new HBox(
            8,
            new Label(I18n.get("snippets.ai.diff.syntax")),
            syntaxCombo,
            spacer,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.add(new Label(I18n.get("snippets.ai.diff.original")), 0, 0);
        grid.add(new Label(I18n.get("snippets.ai.diff.replacement")), 1, 0);
        MonacoEditorPane beforeScrollPane = EditorSettingsHelper.createScrollPane(beforeArea);
        MonacoEditorPane afterScrollPane = EditorSettingsHelper.createScrollPane(afterArea);
        grid.add(beforeScrollPane, 0, 1);
        grid.add(afterScrollPane, 1, 1);
        GridPane.setHgrow(beforeScrollPane, Priority.ALWAYS);
        GridPane.setHgrow(afterScrollPane, Priority.ALWAYS);
        GridPane.setVgrow(beforeScrollPane, Priority.ALWAYS);
        GridPane.setVgrow(afterScrollPane, Priority.ALWAYS);

        VBox root = new VBox(10, summaryLabel, toolbar, grid);
        root.setPadding(new Insets(14));
        VBox.setVgrow(grid, Priority.ALWAYS);

        ButtonType applyButton = new ButtonType(I18n.get("snippets.ai.diff.apply"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(980);
        getDialogPane().setPrefHeight(640);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardShortcut);
        applyPreviewStyle();
        applyHighlighting();
        setResultConverter(buttonType -> buttonType == applyButton);
    }

    private MonacoEditorPane createPreviewArea(String text) {
        MonacoEditorPane area = new MonacoEditorPane();
        area.setEditable(false);
        area.setWrapText(false);
        area.setFocusTraversable(true);
        area.replaceText(text != null ? text : "");
        area.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                focusedPreviewArea = area;
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if ((event.isShortcutDown() || event.isControlDown()) && event.getCode() == KeyCode.C) {
                copyPreviewText(area);
                event.consume();
            }
        });
        return area;
    }

    private void handleKeyboardShortcut(KeyEvent event) {
        if (!event.isShortcutDown() && !event.isControlDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changePreviewFontSize(PREVIEW_FONT_STEP);
            event.consume();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changePreviewFontSize(-PREVIEW_FONT_STEP);
            event.consume();
        }
    }

    private void changePreviewFontSize(int delta) {
        int nextSize = Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, previewSettings.fontSize() + delta));
        if (nextSize == previewSettings.fontSize()) {
            updateFontSizeLabel();
            return;
        }
        previewSettings = new EditorSettingsHelper.Settings(
            previewSettings.fontFamily(),
            nextSize,
            previewSettings.foregroundColor(),
            previewSettings.backgroundColor(),
            previewSettings.cursorStyle(),
            previewSettings.cursorColor());
        applyPreviewStyle();
        applyHighlighting();
        updateFontSizeLabel();
    }

    private void applyPreviewStyle() {
        EditorSettingsHelper.applyStyle(beforeArea, previewSettings);
        EditorSettingsHelper.applyStyle(afterArea, previewSettings);
    }

    private void applyHighlighting() {
        applyHighlighting(beforeArea);
        applyHighlighting(afterArea);
    }

    private void applyHighlighting(MonacoEditorPane area) {
        area.setLanguage(syntaxCombo.getValue());
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(previewSettings.fontSize() + "pt");
        }
    }

    private void copyFocusedPreviewText() {
        copyPreviewText(focusedPreviewArea != null ? focusedPreviewArea : afterArea);
    }

    private void copyPreviewText(MonacoEditorPane area) {
        String selectedText = area.getSelectedText();
        String value = selectedText != null && !selectedText.isEmpty() ? selectedText : area.getText();
        ClipboardContent content = new ClipboardContent();
        content.putString(Objects.requireNonNullElse(value, ""));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static boolean nonBlank(String text) {
        return text != null && !text.isBlank();
    }
}
