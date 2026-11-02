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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shows an external skill's pending update as a side-by-side diff — the library version left, the
 * provider version right — before anything is decided. An unchanged skill is offered the update; a skill the user
 * or the skill optimizer changed is never overwritten, only offered a copy of the provider version or a skip.
 * The result is the chosen {@link ExternalAiSkillUpdateDecision}; closing the dialog decides nothing.
 */
final class ExternalAiSkillUpdateDialog extends ThemeAwareDialog<ExternalAiSkillUpdateDecision> {

    ExternalAiSkillUpdateDialog(Window owner, AiSkill skill, String newRevision, String newContent) {
        boolean locallyModified = ExternalAiSkillSupport.isLocallyModified(skill);
        String name = Objects.requireNonNullElse(skill.getName(), "");
        String revision = ExternalAiSkillSupport.shortRevision(newRevision);
        // A changed skill is not offered the update, so its title and header must not ask whether to apply it.
        setTitle(I18n.get(locallyModified
            ? "settings.aiSkills.external.update.modifiedTitle"
            : "settings.aiSkills.external.update.title"));
        setHeaderText(I18n.get(locallyModified
            ? "settings.aiSkills.external.update.modifiedHeader"
            : "settings.aiSkills.external.update.header", name, revision));
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
        if (locallyModified) {
            Label warning = new Label(I18n.get("settings.aiSkills.external.update.modifiedWarning"));
            warning.setWrapText(true);
            warning.setStyle("-fx-text-fill: #d97706;");
            root.getChildren().add(0, warning);
        }
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);

        Map<ButtonType, ExternalAiSkillUpdateDecision> decisions = new LinkedHashMap<>();
        for (ExternalAiSkillUpdateDecision decision : ExternalAiSkillUpdateDecision.offeredFor(locallyModified)) {
            decisions.put(new ButtonType(label(decision), buttonData(decision)), decision);
        }
        List<ButtonType> buttonTypes = new ArrayList<>(decisions.keySet());
        buttonTypes.add(ButtonType.CANCEL);
        getDialogPane().getButtonTypes().setAll(buttonTypes);
        for (ButtonType decisionButton : decisions.keySet()) {
            ((Button) getDialogPane().lookupButton(decisionButton)).setDefaultButton(false);
        }
        getDialogPane().setPrefWidth(1120);
        getDialogPane().setPrefHeight(720);
        setOnHidden(event -> diffPane.dispose());
        setResultConverter(decisions::get);
    }

    private static String label(ExternalAiSkillUpdateDecision decision) {
        return switch (decision) {
            case APPLY -> I18n.get("settings.aiSkills.external.update.apply");
            case COPY -> I18n.get("settings.aiSkills.external.update.copy");
            case SKIP -> I18n.get("settings.aiSkills.external.update.skip");
        };
    }

    private static ButtonBar.ButtonData buttonData(ExternalAiSkillUpdateDecision decision) {
        return decision == ExternalAiSkillUpdateDecision.APPLY ? ButtonBar.ButtonData.OK_DONE : ButtonBar.ButtonData.OTHER;
    }
}
