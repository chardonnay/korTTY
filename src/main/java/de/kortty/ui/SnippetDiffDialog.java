package de.kortty.ui;

import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.Snippet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;

/**
 * Read-only side-by-side diff for two snippets selected in the snippet manager.
 */
public class SnippetDiffDialog extends ThemeAwareDialog<Void> {

    private final MonacoDiffPane diffPane;

    public SnippetDiffDialog(
            Window owner,
            Snippet leftSnippet,
            Snippet rightSnippet,
            EditorSettingsHelper.Settings editorSettings) {

        Objects.requireNonNull(leftSnippet, "leftSnippet");
        Objects.requireNonNull(rightSnippet, "rightSnippet");

        setTitle(I18n.get("snippets.diff.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        String leftLanguage = detectedLanguage(leftSnippet);
        String rightLanguage = detectedLanguage(rightSnippet);

        Label leftLabel = new Label(I18n.get(
                "snippets.diff.left",
                labelFor(leftSnippet),
                leftLanguage));
        Label rightLabel = new Label(I18n.get(
                "snippets.diff.right",
                labelFor(rightSnippet),
                rightLanguage));
        leftLabel.setMaxWidth(Double.MAX_VALUE);
        rightLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(leftLabel, Priority.ALWAYS);
        HBox.setHgrow(rightLabel, Priority.ALWAYS);

        Region spacer = new Region();
        spacer.setMinWidth(12);
        HBox header = new HBox(12, leftLabel, spacer, rightLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        diffPane = new MonacoDiffPane();
        EditorSettingsHelper.Settings settings =
                editorSettings != null ? editorSettings : EditorSettingsHelper.loadSnippetSettings();
        diffPane.setFont(settings.fontFamily(), settings.fontSize());
        diffPane.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
        diffPane.setStyle("-fx-background-color: " + settings.backgroundColor() + ";");
        diffPane.setComparison(
                Objects.requireNonNullElse(leftSnippet.getContent(), ""),
                Objects.requireNonNullElse(rightSnippet.getContent(), ""),
                leftLanguage,
                rightLanguage);

        VBox root = new VBox(10, header, diffPane);
        root.setPadding(new Insets(14));
        VBox.setVgrow(diffPane, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(1120);
        getDialogPane().setPrefHeight(720);
        setOnHidden(event -> diffPane.dispose());
        setResultConverter(buttonType -> null);
    }

    private static String detectedLanguage(Snippet snippet) {
        return SnippetLanguageSupport.detectSnippetLanguage(
                snippet.getLanguage(),
                Objects.requireNonNullElse(snippet.getContent(), ""));
    }

    private static String labelFor(Snippet snippet) {
        String name = snippet.getName();
        if (name == null || name.isBlank()) {
            return I18n.get("snippets.insertTerminal.unnamed");
        }
        return name;
    }
}
