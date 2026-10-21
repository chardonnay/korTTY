package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * KI-Agent design 09, a tmux pane: the agent looks like one more terminal pane right of the terminal,
 * in the same font and colours, framed by lines. Each step is one line ({@code ✓ read du -xh /var 12 l.}),
 * the answer types itself out behind a block cursor, and a blue status line at the bottom names the
 * panes like tmux does. An approval offers {@code [1] [2] [3]} to click or type. About 40 % of the pane's
 * width. FX thread.
 */
final class AgentDesignTmuxPane extends VBox implements AgentRunPresenter {

    private static final double SHARE = 0.4;
    private static final double MIN_WIDTH = 280;
    private static final double MAX_WIDTH = 640;
    private static final Duration TYPE_STEP = Duration.millis(24);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final AgentRunPresenter.Actions actions;
    private final Label frameTitle = new Label();
    private final TextFlow lines = new TextFlow();
    private final ScrollPane scroller = new ScrollPane(lines);
    private final Text answer = new Text();
    private final Text cursor = new Text("█");
    private final javafx.scene.layout.FlowPane approvalKeys = new javafx.scene.layout.FlowPane(8, 2);
    private final Label status = new Label();
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final FadeTransition blink = new FadeTransition(Duration.millis(530), cursor);
    private final ChangeListener<Number> widthTracker = (obs, oldValue, newValue) -> followShare();
    private Timeline typing;
    private Region measured;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private Font mono = Font.font(AgentDesignWidgets.MONOSPACED, 12);
    private Font monoBold = Font.font(AgentDesignWidgets.MONOSPACED, FontWeight.BOLD, 12);
    private boolean reducedAnimations;
    private String runId;
    private String typedAnswer = "";
    private View view;

    AgentDesignTmuxPane(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        frameTitle.setPadding(new Insets(0, 6, 0, 6));
        lines.setLineSpacing(1);
        lines.setPadding(new Insets(4, 8, 4, 8));
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        approvalKeys.setPadding(new Insets(0, 8, 4, 8));
        approvalKeys.setAlignment(Pos.CENTER_LEFT);
        approvalKeys.setPrefWrapLength(MIN_WIDTH - 16);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setPadding(new Insets(1, 6, 1, 6));
        getChildren().addAll(frameTitle, scroller, approvalKeys, status);
        setMinWidth(MIN_WIDTH);
        setPrefWidth(MIN_WIDTH);
        setMaxWidth(MIN_WIDTH);
        blink.setFromValue(1.0);
        blink.setToValue(0.0);
        blink.setAutoReverse(true);
        blink.setCycleCount(Animation.INDEFINITE);
        // About 40 % of the pane: the slot sits beside the terminal in the pane's row.
        parentProperty().addListener((obs, oldParent, newParent) -> javafx.application.Platform.runLater(this::trackRow));
        visibility.setOnHidden(() -> {
            blink.stop();
            if (typing != null) {
                typing.stop();
            }
        });
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.RIGHT;
    }

    @Override
    public Region node() {
        return this;
    }

    @Override
    public boolean answersApprovals() {
        return true;
    }

    @Override
    public boolean staysAfterRun() {
        return true;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.background()) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border().interpolate(palette.accent(), 0.35)) + ";"
            + " -fx-border-width: 1;");
        frameTitle.setTextFill(palette.accent());
        frameTitle.setFont(monoBold);
        scroller.setStyle(AgentDesignWidgets.scrollerStyle(palette));
        status.setTextFill(AgentDesignPowerlineBar.onColor(palette.accent()));
        status.setFont(mono);
        status.setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.accent()) + ";");
        answer.setFill(palette.text());
        answer.setFont(mono);
        cursor.setFill(palette.accent());
        cursor.setFont(mono);
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            blink.stop();
            cursor.setOpacity(1.0);
        }
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            runId = view.runId();
            typedAnswer = "";
            answer.setText("");
            if (typing != null) {
                typing.stop();
            }
        }
        this.view = view;
        visibility.show();
        followShare();
        frameTitle.setText("─ " + I18n.get("aiAgentDesign.inline.title") + " ─ " + view.meta() + " »");
        rebuildLines();
        refreshApproval(view.trace().pendingApproval);
        status.setText(" " + I18n.get("aiAgentDesign.tmux.panes") + "  " + AgentInlineBlockLayout.oneLine(view.meta())
            + " │ " + LocalTime.now().format(CLOCK));
        if (!reducedAnimations && blink.getStatus() != Animation.Status.RUNNING) {
            blink.play();
        }
    }

    @Override
    public void scheduleAutoHide(int seconds) {
        visibility.scheduleHide(seconds);
    }

    @Override
    public void hideNow() {
        visibility.hideNow();
    }

    @Override
    public boolean isShowing() {
        return visibility.isShowing();
    }

    private void rebuildLines() {
        lines.getChildren().clear();
        add("» ", palette.accent(), true);
        add(AgentInlineBlockLayout.oneLine(view.prompt()) + "\n", palette.text(), true);
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(view.trace())) {
            AgentInlineBlockLayout.Segment marker = AgentInlineBlockLayout.marker(step, "›");
            add(marker.text() + " ", palette.colorOf(marker.role()), true);
            add(String.format("%-6s", AgentStepDetails.kind(step)), palette.think().interpolate(palette.muted(), 0.4), false);
            boolean command = step.type() == TerminalAgentModels.AgentActivityType.ACTION;
            add(AgentInlineBlockLayout.oneLine(command ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step)),
                palette.text(), false);
            String result = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING
                ? AgentInlineBlockLayout.rowMeta(view.trace(), step, System.currentTimeMillis())
                : AgentStepDetails.result(step);
            add(result.isBlank() ? "\n" : "  " + result + "\n", palette.muted(), false);
        }
        if (view.trace().pendingApproval != null) {
            TerminalAgentModels.Approval approval = view.trace().pendingApproval;
            add("? ", palette.warn(), true);
            add(AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)) + "\n", palette.warn(), false);
            add("  $ " + AgentDesignWidgets.approvalCommands(approval) + "\n", palette.text(), true);
        }
        if (!view.running()) {
            typeAnswer(AgentInlineBlockLayout.finalAnswer(view.trace()));
            lines.getChildren().addAll(answer, cursor);
        } else {
            lines.getChildren().add(cursor);
        }
        javafx.application.Platform.runLater(() -> scroller.setVvalue(1.0));
    }

    private void add(String text, Color color, boolean bold) {
        Text part = new Text(text);
        part.setFill(color);
        part.setFont(bold ? monoBold : mono);
        lines.getChildren().add(part);
    }

    /** Types the answer out a character at a time behind the block cursor. */
    private void typeAnswer(String text) {
        String full = text == null ? "" : text.strip();
        if (full.equals(typedAnswer)) {
            return;
        }
        typedAnswer = full;
        if (typing != null) {
            typing.stop();
        }
        if (reducedAnimations || full.isEmpty()) {
            answer.setText(full);
            return;
        }
        answer.setText("");
        typing = new Timeline();
        for (int i = 1; i <= full.length(); i++) {
            String shown = full.substring(0, i);
            typing.getKeyFrames().add(new KeyFrame(TYPE_STEP.multiply(i), event -> answer.setText(shown)));
        }
        typing.play();
    }

    private void refreshApproval(TerminalAgentModels.Approval approval) {
        approvalKeys.getChildren().clear();
        approvalKeys.setVisible(approval != null);
        approvalKeys.setManaged(approval != null);
        if (approval == null) {
            return;
        }
        for (javafx.scene.control.Button button : AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval)) {
            button.setText("[" + button.getText().replaceFirst(" ", "] "));
            button.setStyle("-fx-background-color: transparent; -fx-text-fill: " + AgentDesignPalette.hex(palette.accent())
                + "; -fx-font-family: '" + AgentDesignWidgets.MONOSPACED + "'; -fx-padding: 0 2 0 2; -fx-cursor: hand;");
            approvalKeys.getChildren().add(button);
        }
    }

    /** Listens to the width of the pane's row, so the pane takes its share of it. */
    private void trackRow() {
        Parent slot = getParent();
        Parent row = slot != null ? slot.getParent() : null;
        Region next = row instanceof Region region ? region : null;
        if (next == measured) {
            return;
        }
        if (measured != null) {
            measured.widthProperty().removeListener(widthTracker);
        }
        measured = next;
        if (measured != null) {
            measured.widthProperty().addListener(widthTracker);
        }
        followShare();
    }

    private void followShare() {
        if (measured == null || measured.getWidth() <= 0) {
            return;
        }
        double width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, measured.getWidth() * SHARE));
        if (Math.abs(getPrefWidth() - width) > 1) {
            setPrefWidth(width);
            setMaxWidth(width);
            approvalKeys.setPrefWrapLength(width - 16);
        }
    }
}
