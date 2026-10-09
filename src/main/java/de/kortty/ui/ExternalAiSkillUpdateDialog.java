package de.kortty.ui;

import de.kortty.core.ExternalAiSkillSupport;
import de.kortty.model.AiSkill;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;

/**
 * Shows an external skill's pending update as a side-by-side diff — the library version left, the
 * provider version right — before anything is replaced. The result is {@code true} for "Apply update".
 */
final class ExternalAiSkillUpdateDialog extends ThemeAwareDialog<Boolean> {

    ExternalAiSkillUpdateDialog(Window owner, AiSkill skill, String newRevision, String newContent) {
        setTitle(I18n.get("settings.aiSkills.external.update.title"));
        setHeaderText(I18n.get("settings.aiSkills.external.update.header",
            Objects.requireNonNullElse(skill.getName(), ""), ExternalAiSkillSupport.shortRevision(newRevision)));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        Label leftLabel = new Label(I18n.get("settings.aiSkills.external.update.left"));
        Label rightLabel = new Label(I18n.get("settings.aiSkills.external.update.right"));
        leftLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(leftLabel, Priority.ALWAYS);
        rightLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rightLabel, Priority.ALWAYS);
        HBox header = new HBox(12, leftLabel, rightLabel);

        MonacoDiffPane diffPane = new MonacoDiffPane();
        EditorSettingsHelper.Settings settings = EditorSettingsHelper.loadSnippetSettings();
        diffPane.setFont(settings.fontFamily(), settings.fontSize());
        diffPane.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
        diffPane.setStyle("-fx-background-color: " + settings.backgroundColor() + ";");
        diffPane.setComparison(Objects.requireNonNullElse(skill.getContent(), ""),
            Objects.requireNonNullElse(newContent, ""), "markdown", "markdown");
        VBox.setVgrow(diffPane, Priority.ALWAYS);

        VBox root = new VBox(10, header, diffPane);
        if (ExternalAiSkillSupport.isLocallyModified(skill)) {
            Label warning = new Label(I18n.get("settings.aiSkills.external.update.modifiedWarning"));
            warning.setWrapText(true);
            warning.setStyle("-fx-text-fill: #d97706;");
            root.getChildren().add(0, warning);
        }
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);

        ButtonType apply = new ButtonType(I18n.get("settings.aiSkills.external.update.apply"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(apply, ButtonType.CANCEL);
        ((Button) getDialogPane().lookupButton(apply)).setDefaultButton(false);
        getDialogPane().setPrefWidth(1120);
        getDialogPane().setPrefHeight(720);
        setOnHidden(event -> diffPane.dispose());
        setResultConverter(buttonType -> buttonType == apply);
    }
}
