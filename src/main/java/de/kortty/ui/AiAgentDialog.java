package de.kortty.ui;

import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Dialog used to start an AI agent run or a non-executing agent ask request.
 */
public class AiAgentDialog extends ThemeAwareDialog<TerminalAgentModels.Request> {

    private final ComboBox<AiProfile> profileComboBox;
    private final TextArea promptArea;
    private final CheckBox showDebugMessagesCheck;
    private final CheckBox showRuntimeMessagesCheck;

    public AiAgentDialog(
        Window owner,
        List<AiProfile> profiles,
        String connectionDisplayName,
        TerminalAgentExecutionTarget executionTarget,
        boolean showDebugMessages,
        boolean showRuntimeMessages,
        boolean queryOnly,
        String initialPrompt) {
        initOwner(owner);
        setResizable(true);
        setTitle(I18n.get(queryOnly ? "ai.agent.ask.title" : "ai.agent.title"));
        setHeaderText(I18n.get(queryOnly ? "ai.agent.ask.header" : "ai.agent.header"));

        ButtonType runButtonType = new ButtonType(
            I18n.get(queryOnly ? "ai.agent.ask.start" : "ai.agent.start"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(runButtonType, ButtonType.CANCEL);

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

        Label connectionLabel = new Label(I18n.get(
            queryOnly ? "ai.agent.ask.connection" : "ai.agent.connection",
            connectionDisplayName != null && !connectionDisplayName.isBlank()
                ? connectionDisplayName
                : I18n.get("ai.agent.connection.unknown")));
        connectionLabel.setWrapText(true);

        Label targetLabel = new Label(I18n.get(
            "ai.agent.executionTarget.value",
            I18n.get(executionTarget == TerminalAgentExecutionTarget.CHAT_WINDOW
                ? "ai.agent.executionTarget.chatWindow"
                : "ai.agent.executionTarget.terminalWindow")));
        targetLabel.setWrapText(true);

        promptArea = new TextArea(initialPrompt != null ? initialPrompt : "");
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(6);
        promptArea.setPromptText(I18n.get(queryOnly ? "ai.agent.ask.prompt" : "ai.agent.prompt"));

        showDebugMessagesCheck = new CheckBox(I18n.get("ai.agent.showDebug"));
        showDebugMessagesCheck.setSelected(showDebugMessages);
        showRuntimeMessagesCheck = new CheckBox(I18n.get("ai.agent.showRuntime"));
        showRuntimeMessagesCheck.setSelected(showRuntimeMessages);
        showRuntimeMessagesCheck.setDisable(queryOnly);

        VBox content = new VBox(
            10,
            new Label(I18n.get("ai.agent.profile")),
            profileComboBox,
            connectionLabel,
            targetLabel,
            new Label(I18n.get(queryOnly ? "ai.agent.ask.prompt.label" : "ai.agent.prompt.label")),
            promptArea,
            showDebugMessagesCheck,
            showRuntimeMessagesCheck);
        content.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(promptArea, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(720, queryOnly ? 420 : 460);

        Button runButton = (Button) getDialogPane().lookupButton(runButtonType);
        runButton.setDisable(profileComboBox.getSelectionModel().getSelectedItem() == null
            || promptArea.getText().trim().isEmpty());
        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
            runButton.setDisable(newValue == null || promptArea.getText().trim().isEmpty()));
        promptArea.textProperty().addListener((obs, oldValue, newValue) ->
            runButton.setDisable(profileComboBox.getSelectionModel().getSelectedItem() == null || newValue.trim().isEmpty()));

        setResultConverter(buttonType -> {
            if (buttonType != runButtonType) {
                return null;
            }
            AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
            if (profile == null) {
                return null;
            }
            return new TerminalAgentModels.Request(
                null,
                profile.getId(),
                promptArea.getText().trim(),
                connectionDisplayName,
                null,
                executionTarget,
                showDebugMessagesCheck.isSelected(),
                !queryOnly && showRuntimeMessagesCheck.isSelected(),
                false,
                false,
                false,
                queryOnly);
        });
    }

    private String displayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }
}
