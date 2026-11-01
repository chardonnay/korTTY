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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
    private final CheckBox mirrorFinalAnswerCheck;

    public AiAgentDialog(
        Window owner,
        List<AiProfile> profiles,
        String connectionDisplayName,
        TerminalAgentExecutionTarget executionTarget,
        boolean showDebugMessages,
        boolean showRuntimeMessages,
        boolean mirrorFinalAnswerToTerminal,
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
        installPromptMenu(promptArea);

        showDebugMessagesCheck = new CheckBox(I18n.get("ai.agent.showDebug"));
        showDebugMessagesCheck.setSelected(showDebugMessages);
        showRuntimeMessagesCheck = new CheckBox(I18n.get("ai.agent.showRuntime"));
        showRuntimeMessagesCheck.setSelected(showRuntimeMessages);
        showRuntimeMessagesCheck.setDisable(queryOnly);

        // Mirroring the final answer into the terminal only applies to an executing run shown in the
        // terminal window; the ask/chat-window paths have no terminal to write to.
        boolean mirrorApplies = !queryOnly && executionTarget == TerminalAgentExecutionTarget.TERMINAL_WINDOW;
        mirrorFinalAnswerCheck = new CheckBox(I18n.get("ai.agent.mirrorToTerminal"));
        mirrorFinalAnswerCheck.setSelected(mirrorFinalAnswerToTerminal);
        mirrorFinalAnswerCheck.setDisable(!mirrorApplies);

        VBox content = new VBox(
            10,
            new Label(I18n.get("ai.agent.profile")),
            profileComboBox,
            connectionLabel,
            targetLabel,
            new Label(I18n.get(queryOnly ? "ai.agent.ask.prompt.label" : "ai.agent.prompt.label")),
            promptArea,
            showDebugMessagesCheck,
            showRuntimeMessagesCheck,
            mirrorFinalAnswerCheck);
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
                queryOnly,
                mirrorFinalAnswerCheck.isSelected());
        });
    }

    private String displayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    /**
     * The prompt's menu: the edit actions, and "Als Regel merken…" for the selected text or the whole prompt. The rule entry
     * is disabled, with its text saying so, when no place for a rule is allowed.
     */
    private void installPromptMenu(TextArea area) {
        MenuItem cut = new MenuItem(I18n.get("menu.edit.cut"));
        cut.setOnAction(event -> area.cut());
        MenuItem copy = new MenuItem(I18n.get("menu.edit.copy"));
        copy.setOnAction(event -> area.copy());
        MenuItem paste = new MenuItem(I18n.get("menu.edit.paste"));
        paste.setOnAction(event -> area.paste());
        MenuItem selectAll = new MenuItem(I18n.get("menu.edit.selectAll"));
        selectAll.setOnAction(event -> area.selectAll());
        MenuItem remember = new MenuItem(I18n.get("ai.learn.menu"));
        remember.setOnAction(event -> {
            String selected = area.getSelectedText();
            RuleLearning.rememberManually(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                selected == null || selected.isBlank() ? area.getText() : selected, null);
        });
        ContextMenu menu = new ContextMenu(cut, copy, paste, new SeparatorMenuItem(), selectAll,
            new SeparatorMenuItem(), remember);
        menu.setOnShowing(event -> {
            boolean hasSelection = area.getSelectedText() != null && !area.getSelectedText().isEmpty();
            cut.setDisable(!hasSelection);
            copy.setDisable(!hasSelection);
            boolean allowed = RuleLearning.manualRuleAllowed();
            remember.setDisable(!allowed);
            remember.setText(I18n.get(allowed ? "ai.learn.menu" : "ai.learn.menu.unavailable"));
        });
        area.setContextMenu(menu);
    }
}
