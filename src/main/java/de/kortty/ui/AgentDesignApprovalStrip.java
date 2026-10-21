package de.kortty.ui;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

/**
 * The row below the terminal where an approval or a password request of a KI-Agent run is answered
 * with the mouse, for designs that have no panel of their own. Approvals can also be answered with the
 * keys 1, 2 and 3 in the terminal; a password needs this row. Hidden while nothing is pending.
 */
final class AgentDesignApprovalStrip extends HBox {

    private final Label icon = new Label("?");
    private final Label message = new Label();
    private final HBox controls = new HBox(6);

    AgentDesignApprovalStrip() {
        getStyleClass().add("ai-agent-design-strip");
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(5, 10, 5, 10));
        setMinHeight(Region.USE_PREF_SIZE);
        icon.getStyleClass().add("ai-agent-design-attention");
        message.getStyleClass().add("ai-agent-design-prompt");
        message.setMinWidth(0);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(icon, message, controls);
        hideStrip();
    }

    void showApproval(TerminalAgentModels.Approval approval, Consumer<TerminalAgentService.ApprovalDecision> answer) {
        String commands = approval != null && approval.commands() != null
            ? approval.commands().stream()
                .filter(command -> command != null && command.command() != null)
                .map(command -> command.command().strip())
                .reduce((left, right) -> left + " ; " + right)
                .orElse("")
            : "";
        String text = approval != null && approval.userMessage() != null && !approval.userMessage().isBlank()
            ? approval.userMessage().strip()
            : I18n.get("ai.agent.activity.approvalPrompt");
        message.setText(commands.isBlank() ? text : I18n.get("aiAgentDesign.strip.approval", commands));
        message.setTooltip(new javafx.scene.control.Tooltip(commands.isBlank() ? text : text + "\n\n$ " + commands));
        boolean allowAlways = approval != null && approval.allowAlways();
        Button once = button("1 " + I18n.get("ai.agent.approval.once"), true);
        once.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.APPROVE_ONCE));
        controls.getChildren().setAll(once);
        if (allowAlways) {
            Button always = button("2 " + I18n.get("ai.agent.approval.always"), false);
            always.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS));
            controls.getChildren().add(always);
        }
        Button cancel = button((allowAlways ? "3 " : "2 ") + I18n.get("dialog.cancel"), false);
        cancel.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.CANCEL));
        controls.getChildren().add(cancel);
        showStrip();
    }

    void showPassword(TerminalAgentModels.PasswordRequest request, Consumer<TerminalAgentModels.PasswordResponse> answer) {
        String summary = request != null && request.summary() != null && !request.summary().isBlank()
            ? request.summary().strip()
            : I18n.get("ai.agent.password.title");
        message.setText(request != null && request.command() != null && !request.command().isBlank()
            ? summary + "  $ " + request.command().strip()
            : summary);
        PasswordField field = new PasswordField();
        field.getStyleClass().add("ai-agent-password-field");
        field.setPromptText(I18n.get("common.password"));
        field.setPrefColumnCount(14);
        CheckBox cache = new CheckBox(I18n.get("ai.agent.password.cacheForSession"));
        cache.getStyleClass().add("ai-agent-option-check");
        cache.setSelected(true);
        Button ok = button(I18n.get("dialog.ok"), true);
        ok.disableProperty().bind(field.textProperty().isEmpty());
        Runnable submit = () -> {
            if (!field.getText().isEmpty()) {
                answer.accept(new TerminalAgentModels.PasswordResponse(field.getText(), cache.isSelected()));
            }
        };
        ok.setOnAction(event -> submit.run());
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                submit.run();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                answer.accept(null);
                event.consume();
            }
        });
        Button cancel = button(I18n.get("dialog.cancel"), false);
        cancel.setOnAction(event -> answer.accept(null));
        controls.getChildren().setAll(field, cache, ok, cancel);
        showStrip();
        javafx.application.Platform.runLater(field::requestFocus);
    }

    void hideStrip() {
        controls.getChildren().clear();
        message.setText("");
        setVisible(false);
        setManaged(false);
    }

    private void showStrip() {
        setVisible(true);
        setManaged(true);
    }

    private static Button button(String text, boolean primary) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.getStyleClass().add("ai-agent-design-button");
        if (primary) {
            button.getStyleClass().add("ai-agent-design-primary");
        }
        return button;
    }
}
