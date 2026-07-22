package de.kortty.ui;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiTextSupport;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dialog that shows an AI-generated technical description for a snippet selection or full snippet.
 * Provides a persisted font zoom, copy/insert actions and — when a re-run callback is supplied — a
 * transient AI-profile picker plus a re-run button to regenerate the description with a different
 * profile, keeping the dialog consistent with the other snippet AI dialogs.
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
    private int previewFontSize;

    public SnippetDescriptionDialog(
        Window owner,
        String rawDescription,
        String snippetLanguage,
        String indentation,
        Consumer<String> insertHandler) {

        this(owner, rawDescription, snippetLanguage, indentation, insertHandler, null, null);
    }

    public SnippetDescriptionDialog(
        Window owner,
        String rawDescription,
        String snippetLanguage,
        String indentation,
        Consumer<String> insertHandler,
        String activeProfileId,
        Consumer<String> onRerun) {

        this.rawDescription = rawDescription != null ? rawDescription : "";
        this.snippetLanguage = snippetLanguage;
        this.indentation = indentation != null ? indentation : "";
        this.insertHandler = insertHandler != null ? insertHandler : text -> {
        };
        this.previewFontSize = clampFontSize(loadPersistedFontSize());

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

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-PREVIEW_FONT_STEP));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changePreviewFontSize(PREVIEW_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();

        Button copyButton = new Button("⧉");
        copyButton.setTooltip(new Tooltip(I18n.get("snippets.ai.describe.copy")));
        copyButton.setOnAction(event -> copyToClipboard(previewArea.getText()));

        Button insertButton = new Button("↥");
        insertButton.setTooltip(new Tooltip(I18n.get("snippets.ai.describe.insert")));
        insertButton.setOnAction(event -> {
            this.insertHandler.accept(previewArea.getText());
            close();
        });

        Label infoLabel = new Label(I18n.get("snippets.ai.describe.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Region spacer = new Region();
        HBox toolbar = new HBox(
            10,
            commentSyntaxCheckBox,
            new Label(I18n.get("snippets.ai.describe.lineWidth")),
            lineWidthSpinner,
            spacer,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton,
            insertButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        updateLineWidthState();

        VBox root = new VBox(10, infoLabel);
        if (onRerun != null) {
            root.getChildren().add(buildProfileBar(activeProfileId, onRerun));
        }
        root.getChildren().addAll(toolbar, previewArea);
        root.setPadding(new Insets(14));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(560);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardZoom);
    }

    private HBox buildProfileBar(String activeProfileId, Consumer<String> onRerun) {
        ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
            SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
        Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
            () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, this::close);
        HBox bar = new HBox(8, SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
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
        int nextSize = clampFontSize(previewFontSize + delta);
        if (nextSize == previewFontSize) {
            return;
        }
        previewFontSize = nextSize;
        applyPreviewFontSize();
        updateFontSizeLabel();
        persistFontSize();
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
        de.kortty.core.KorttyClipboard.setText(value);
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, size));
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        if (settings != null && settings.getAiDescribeFontSize() != null) {
            return settings.getAiDescribeFontSize();
        }
        return DEFAULT_PREVIEW_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiDescribeFontSize(previewFontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
