package de.kortty.ui;

import de.kortty.core.SnippetAiTextSupport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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

    private final String rawDescription;
    private final String snippetLanguage;
    private final String indentation;
    private final Consumer<String> insertHandler;
    private final TextArea previewArea;
    private final CheckBox commentSyntaxCheckBox;

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
        commentSyntaxCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> refreshPreview());

        previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setWrapText(true);
        previewArea.setPrefRowCount(16);
        VBox.setVgrow(previewArea, Priority.ALWAYS);
        refreshPreview();

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

        HBox toolbar = new HBox(10, commentSyntaxCheckBox, copyButton, insertButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, infoLabel, toolbar, previewArea);
        root.setPadding(new Insets(14));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(540);
    }

    private void refreshPreview() {
        String previewText = commentSyntaxCheckBox.isSelected()
            ? SnippetAiTextSupport.formatDescriptionAsComment(rawDescription, snippetLanguage, indentation)
            : SnippetAiTextSupport.normalizePlainText(rawDescription);
        previewArea.setText(previewText);
    }

    private void copyToClipboard(String text) {
        String value = Objects.requireNonNullElse(text, "");
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
