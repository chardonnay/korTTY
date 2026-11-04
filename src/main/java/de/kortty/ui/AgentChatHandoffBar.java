package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The line that offers to continue a finished KI-Agent run in a KI-chat: a status with the run's task,
 * the button "Im KI-Chat fortsetzen", a field for a follow-up question (Enter opens the chat and sends
 * it; an empty Enter only opens it) and a dismiss button. The host shows it under the run's rows in
 * every design, and in the classic design too. Its colours come from the design's palette; until a
 * palette is applied it stays plain. FX thread only.
 */
final class AgentChatHandoffBar extends HBox {

    /** What the bar's controls do; the host passes its run and handler. */
    interface Actions {
        /**
         * Opens the run's chat, sending {@code followUp} there when it is not blank. Returns whether the chat
         * opened and the question went into it; when it did not, the bar keeps the typed question.
         */
        boolean continueInChat(String followUp);

        /** Hides the bar until the next finished run. */
        void dismiss();
    }

    private final Actions actions;
    private final Label status = new Label();
    private final TextField question = new TextField();
    private @Nullable AgentDesignPalette palette;
    private Button continueButton;
    private Button dismissButton;
    private boolean available = true;
    private boolean finishedCleanly = true;
    // The run the bar is offered for; a question typed for another run is not sent to this one.
    private @Nullable String runId;

    AgentChatHandoffBar(Actions actions) {
        this.actions = actions;
        getStyleClass().add("ai-agent-design-strip");
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 10, 2, 10));
        status.setMinWidth(0);
        status.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        status.setStyle("-fx-font-size: 0.8462em;");
        HBox.setHgrow(status, Priority.SOMETIMES);
        question.setPromptText(I18n.get("aiAgentDesign.chatHandoff.field"));
        question.setTooltip(new Tooltip(I18n.get("aiAgentDesign.chatHandoff.fieldTooltip")));
        question.setAccessibleText(I18n.get("aiAgentDesign.chatHandoff.field"));
        question.setPrefColumnCount(22);
        HBox.setHgrow(question, Priority.SOMETIMES);
        question.setOnAction(event -> continueWith(question.getText()));
        continueButton = buildContinueButton();
        dismissButton = buildDismissButton();
        rebuildChildren();
        hideBar();
    }

    /**
     * Shows the offer for a finished run. {@code runId} is the run, {@code runTitle} its task (shortened by the
     * caller), {@code finishedCleanly} picks the status glyph, and {@code available} says whether the chat can
     * be opened now (AI features on, a handler set); when it cannot, the controls stay visible but disabled.
     * A question typed for the run shown before is dropped when the offer moves to another run.
     */
    void show(String runId, String runTitle, boolean finishedCleanly, boolean available) {
        if (!Objects.equals(this.runId, runId)) {
            question.clear();
        }
        this.runId = runId;
        this.available = available;
        this.finishedCleanly = finishedCleanly;
        status.setText((finishedCleanly ? "✓ " : "✕ ")
            + I18n.get(finishedCleanly ? "aiAgentDesign.chatHandoff.finished" : "aiAgentDesign.chatHandoff.stopped")
            + (runTitle == null || runTitle.isBlank() ? "" : " · " + runTitle));
        applyStatusColor(finishedCleanly);
        rebuildChildren();
        setVisible(true);
        setManaged(true);
    }

    /** Hides the bar and forgets the typed question. */
    void hideBar() {
        question.clear();
        runId = null;
        setVisible(false);
        setManaged(false);
    }

    /** Applies the design's colours and rebuilds the controls in them. */
    void applyPalette(AgentDesignPalette designPalette) {
        this.palette = designPalette;
        continueButton = buildContinueButton();
        dismissButton = buildDismissButton();
        question.setStyle("-fx-background-color: " + AgentDesignPalette.rgba(designPalette.surface(), 0.9)
            + "; -fx-text-fill: " + AgentDesignPalette.hex(designPalette.text())
            + "; -fx-prompt-text-fill: " + AgentDesignPalette.hex(designPalette.muted())
            + "; -fx-border-color: " + AgentDesignPalette.hex(designPalette.border())
            + "; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 2 6 2 6; -fx-font-size: 0.8462em;");
        status.setTextFill(designPalette.text());
        rebuildChildren();
        applyStatusColor(finishedCleanly);
    }

    /** Lays out the controls and sets their availability and the button's tooltip to what the run allows now. */
    private void rebuildChildren() {
        getChildren().setAll(status, continueButton, question, dismissButton);
        continueButton.setDisable(!available);
        continueButton.setTooltip(new Tooltip(I18n.get(available
            ? "aiAgentDesign.chatHandoff.tooltip"
            : "aiAgentDesign.chatHandoff.unavailable")));
        question.setDisable(!available);
    }

    private void applyStatusColor(boolean finishedCleanly) {
        if (palette == null) {
            return;
        }
        status.setTextFill(finishedCleanly ? palette.ok() : palette.error());
    }

    private Button buildContinueButton() {
        Button button = palette != null
            ? AgentDesignWidgets.button(I18n.get("aiAgentDesign.chatHandoff.button"), palette, true)
            : new Button(I18n.get("aiAgentDesign.chatHandoff.button"));
        ButtonIcons.apply(button, ButtonIcons.OPEN);
        button.setAccessibleText(I18n.get("aiAgentDesign.chatHandoff.button"));
        button.setOnAction(event -> continueWith(question.getText()));
        return button;
    }

    private Button buildDismissButton() {
        Button button = palette != null
            ? AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.chatHandoff.dismiss"), palette)
            : new Button("✕");
        button.setTooltip(new Tooltip(I18n.get("aiAgentDesign.chatHandoff.dismiss")));
        button.setAccessibleText(I18n.get("aiAgentDesign.chatHandoff.dismiss"));
        button.setFocusTraversable(true);
        button.setOnAction(event -> actions.dismiss());
        return button;
    }

    private void continueWith(@Nullable String text) {
        if (!available) {
            return;
        }
        // The host hides the bar (and clears the question) only once the chat has taken it.
        actions.continueInChat(text != null ? text.strip() : "");
    }
}
