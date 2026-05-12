package de.kortty.ui;

import de.kortty.core.SnippetAiTextSupport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dialog that shows an AI-generated technical description for a snippet selection or full snippet.
 */
public class SnippetDescriptionDialog extends ThemeAwareDialog<Void> {

    private static final int DEFAULT_PREVIEW_FONT_SIZE = 14;
    private static final int MIN_PREVIEW_FONT_SIZE = 8;
    private static final int MAX_PREVIEW_FONT_SIZE = 32;
    private static final int PREVIEW_FONT_STEP = 1;
    private static final int MIN_COMMENT_LINE_WIDTH = 40;
    private static final int MAX_COMMENT_LINE_WIDTH = 200;
    private static final int COMMENT_LINE_WIDTH_STEP = 5;

    private final String rawDescription;
    private final String snippetLanguage;
    private final String indentation;
    private final Consumer<String> insertHandler;
    private final TextArea previewArea;
    private final CheckBox commentSyntaxCheckBox;
    private final Spinner<Integer> lineWidthSpinner;
    private final Label fontSizeLabel;
    private int previewFontSize = DEFAULT_PREVIEW_FONT_SIZE;

    public SnippetDescriptionDialog(
        Window owner,
        String rawDescription,
        String snippetLanguage,
        String indentation,
        Consumer<String> insertHandler) {

        this.rawDescription = rawDescription != null ? rawDescription : "";
        this.snippetLanguage = snippetLanguage;
        this.indentation = indentation != null ? indentation : "";
        this.insertHandler = insertHandler != null ? insertHandler : text -> {
        };

        setTitle(I18n.get("snippets.ai.describe.dialog.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        commentSyntaxCheckBox = new CheckBox(I18n.get("snippets.ai.describe.commentSyntax"));
        commentSyntaxCheckBox.setSelected(false);
        commentSyntaxCheckBox.setDisable(!SnippetAiTextSupport.supportsCommentFormatting(snippetLanguage));
        commentSyntaxCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            updateLineWidthState();
            refreshPreview();
        });

        lineWidthSpinner = new Spinner<>(
            MIN_COMMENT_LINE_WIDTH,
            MAX_COMMENT_LINE_WIDTH,
            SnippetAiTextSupport.DEFAULT_DESCRIPTION_WRAP_WIDTH,
            COMMENT_LINE_WIDTH_STEP);
        lineWidthSpinner.setEditable(true);
        lineWidthSpinner.setPrefWidth(90);
        lineWidthSpinner.valueProperty().addListener((obs, oldValue, newValue) -> refreshPreview());

        previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setWrapText(true);
        previewArea.setPrefRowCount(16);
        applyPreviewFontSize();
        VBox.setVgrow(previewArea, Priority.ALWAYS);
        refreshPreview();

        Button zoomOutButton = new Button("A-");
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-PREVIEW_FONT_STEP));
        Button zoomInButton = new Button("A+");
        zoomInButton.setOnAction(event -> changePreviewFontSize(PREVIEW_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();

        Button copyButton = new Button("\u29c9");
        copyButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("snippets.ai.describe.copy")));
        copyButton.setOnAction(event -> copyToClipboard(previewArea.getText()));

        Button insertButton = new Button("\u21a5");
        insertButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("snippets.ai.describe.insert")));
        insertButton.setOnAction(event -> {
            insertHandler.accept(previewArea.getText());
            close();
        });

        Label infoLabel = new Label(I18n.get("snippets.ai.describe.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        HBox toolbar = new HBox(
            10,
            commentSyntaxCheckBox,
            new Label(I18n.get("snippets.ai.describe.lineWidth")),
            lineWidthSpinner,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton,
            insertButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        updateLineWidthState();

        VBox root = new VBox(10, infoLabel, toolbar, previewArea);
        root.setPadding(new Insets(14));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(540);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardZoom);
    }

    private void refreshPreview() {
        String previewText = commentSyntaxCheckBox.isSelected()
            ? SnippetAiTextSupport.formatDescriptionAsComment(rawDescription, snippetLanguage, indentation, currentLineWidth())
            : SnippetAiTextSupport.normalizePlainText(rawDescription);
        previewArea.setText(previewText);
    }

    private int currentLineWidth() {
        Integer value = lineWidthSpinner.getValue();
        if (value == null) {
            return SnippetAiTextSupport.DEFAULT_DESCRIPTION_WRAP_WIDTH;
        }
        return Math.max(MIN_COMMENT_LINE_WIDTH, Math.min(MAX_COMMENT_LINE_WIDTH, value));
    }

    private void updateLineWidthState() {
        boolean enabled = commentSyntaxCheckBox.isSelected() && !commentSyntaxCheckBox.isDisabled();
        lineWidthSpinner.setDisable(!enabled);
    }

    private void handleKeyboardZoom(KeyEvent event) {
        if (!event.isControlDown() && !event.isShortcutDown()) {
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
        int nextSize = Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, previewFontSize + delta));
        if (nextSize == previewFontSize) {
            return;
        }
        previewFontSize = nextSize;
        applyPreviewFontSize();
        updateFontSizeLabel();
    }

    private void applyPreviewFontSize() {
        previewArea.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: " + previewFontSize + "px;");
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(previewFontSize + "pt");
        }
    }

    private void copyToClipboard(String text) {
        String value = Objects.requireNonNullElse(text, "");
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
