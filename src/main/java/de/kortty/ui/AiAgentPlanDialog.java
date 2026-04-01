package de.kortty.ui;

import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentModels;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Dialog used to start an AI planning-only run for the active SSH session.
 */
public class AiAgentPlanDialog extends ThemeAwareDialog<TerminalAgentModels.PlanRequest> {

    private final ComboBox<AiProfile> profileComboBox;
    private final TextArea promptArea;

    public AiAgentPlanDialog(Window owner, List<AiProfile> profiles, String connectionDisplayName, String initialPrompt) {
        initOwner(owner);
        setResizable(true);
        setTitle(I18n.get("ai.plan.title"));
        setHeaderText(I18n.get("ai.plan.header"));

        ButtonType startButtonType = new ButtonType(I18n.get("ai.plan.start"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(startButtonType, ButtonType.CANCEL);

        profileComboBox = new ComboBox<>();
        profileComboBox.getItems().setAll(profiles);
        profileComboBox.setPrefWidth(360);
        profileComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : displayName(item));
            }
        });
        profileComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : displayName(item));
            }
        });
        if (!profiles.isEmpty()) {
            profileComboBox.getSelectionModel().selectFirst();
        }

        promptArea = new TextArea(initialPrompt != null ? initialPrompt : "");
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(6);
        promptArea.setPromptText(I18n.get("ai.plan.prompt"));

        VBox content = new VBox(
            10,
            new Label(I18n.get("ai.agent.profile")),
            profileComboBox,
            new Label(I18n.get("ai.plan.connection",
                connectionDisplayName != null && !connectionDisplayName.isBlank()
                    ? connectionDisplayName
                    : I18n.get("ai.agent.connection.unknown"))),
            new Label(I18n.get("ai.plan.prompt.label")),
            promptArea);
        content.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(promptArea, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(720, 420);

        Button startButton = (Button) getDialogPane().lookupButton(startButtonType);
        startButton.setDisable(profileComboBox.getSelectionModel().getSelectedItem() == null
            || promptArea.getText().trim().isEmpty());
        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
            startButton.setDisable(newValue == null || promptArea.getText().trim().isEmpty()));
        promptArea.textProperty().addListener((obs, oldValue, newValue) ->
            startButton.setDisable(profileComboBox.getSelectionModel().getSelectedItem() == null || newValue.trim().isEmpty()));

        setResultConverter(buttonType -> {
            if (buttonType != startButtonType) {
                return null;
            }
            AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
            if (profile == null) {
                return null;
            }
            return new TerminalAgentModels.PlanRequest(null, profile.getId(), promptArea.getText().trim(), connectionDisplayName);
        });
    }

    private String displayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }
}
