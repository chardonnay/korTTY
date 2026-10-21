package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KI-Agent design 06, plan and execution: below the terminal, two columns. Left the run's steps as a
 * checklist whose ticks draw themselves as steps finish; right only what is running now, the command
 * with its exit code and its last output lines, or an open approval with its buttons. A thin bar on
 * top fills with the progress. FX thread.
 */
final class AgentDesignPlanExecution extends VBox implements AgentRunPresenter {

    static final double HEIGHT = 152;
    private static final int OUTPUT_LINES = 5;
    private static final String TICK = "M2 7 L6 11 L13 3";

    private final AgentRunPresenter.Actions actions;
    private final ProgressBar progress = new ProgressBar(0);
    private final Label prompt = new Label();
    private final Label meta = new Label();
    private final Button pauseButton;
    private final Button cancelButton;
    private final Button hideButton;
    private final VBox plan = new VBox(2);
    private final ScrollPane planScroller = new ScrollPane(plan);
    private final ScrollPane executionScroller = new ScrollPane();
    private final VBox execution = new VBox(3);
    private final SplitPane columns = new SplitPane();
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    // The tick of each finished step, kept so a tick that is drawing itself is not replaced on the next frame.
    private final Map<String, Node> ticks = new HashMap<>();
    private final Timeline spinner;
    private Timeline progressFill;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private String runId;
    private View view;
    private int frame;

    AgentDesignPlanExecution(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        pauseButton = AgentDesignWidgets.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"), palette);
        cancelButton = AgentDesignWidgets.iconButton("■", I18n.get("dialog.cancel"), palette);
        hideButton = AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"), palette);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        prompt.setMinWidth(0);
        HBox.setHgrow(prompt, Priority.ALWAYS);
        prompt.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(6, prompt, meta, pauseButton, cancelButton, hideButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 2, 10));
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(3);
        progress.setMinHeight(3);
        progress.setMaxHeight(3);
        planScroller.setFitToWidth(true);
        planScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        plan.setPadding(new Insets(4, 8, 4, 10));
        execution.setPadding(new Insets(4, 10, 4, 10));
        executionScroller.setContent(execution);
        executionScroller.setFitToWidth(true);
        executionScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        columns.getItems().addAll(planScroller, executionScroller);
        columns.setDividerPositions(0.42);
        columns.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-box-border: transparent;");
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().addAll(progress, header, columns);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        // The divider glows under the mouse.
        columns.skinProperty().addListener((obs, oldSkin, newSkin) -> javafx.application.Platform.runLater(() -> {
            for (Node divider : columns.lookupAll(".split-pane-divider")) {
                divider.setOnMouseEntered(event -> divider.setEffect(new DropShadow(8, palette.accent())));
                divider.setOnMouseExited(event -> divider.setEffect(null));
            }
        }));
        spinner = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            frame++;
            if (view != null) {
                refresh();
            }
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(spinner::stop);
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.BOTTOM;
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
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 1 0 0 0;");
        progress.setStyle("-fx-accent: " + AgentDesignPalette.hex(palette.accent()) + ";"
            + " -fx-control-inner-background: " + AgentDesignPalette.hex(palette.surface()) + ";"
            + " -fx-background-color: transparent; -fx-padding: 0; -fx-background-insets: 0;");
        prompt.setTextFill(palette.text());
        prompt.setStyle("-fx-font-weight: bold;");
        meta.setTextFill(palette.muted());
        planScroller.setStyle(AgentDesignWidgets.scrollerStyle(palette));
        executionScroller.setStyle(AgentDesignWidgets.scrollerStyle(palette));
        ticks.clear();
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            spinner.stop();
        }
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            runId = view.runId();
            ticks.clear();
        }
        this.view = view;
        visibility.show();
        boolean animate = view.running() && !view.paused() && !reducedAnimations;
        if (animate && spinner.getStatus() != Animation.Status.RUNNING) {
            spinner.play();
        } else if (!animate) {
            spinner.stop();
        }
        refresh();
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

    private void refresh() {
        prompt.setText(AgentInlineBlockLayout.oneLine(view.prompt()));
        meta.setText(view.meta());
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        List<TerminalAgentModels.AgentActivity> steps = AgentInlineBlockLayout.listedSteps(view.trace());
        long done = steps.stream().filter(s -> s.status() != TerminalAgentModels.AgentActivityStatus.RUNNING).count();
        double target = view.running() ? (steps.isEmpty() ? 0.05 : (double) done / (steps.size() + 1)) : 1.0;
        if (progressFill == null || Math.abs(progress.getProgress() - target) > 0.001) {
            if (progressFill != null) {
                progressFill.stop();
            }
            progressFill = new Timeline(new KeyFrame(Duration.millis(reducedAnimations ? 1 : 360),
                new KeyValue(progress.progressProperty(), target, Interpolator.EASE_BOTH)));
            progressFill.play();
        }
        refreshPlan(steps);
        refreshExecution(steps);
    }

    private void refreshPlan(List<TerminalAgentModels.AgentActivity> steps) {
        plan.getChildren().clear();
        Label caption = AgentDesignWidgets.caption(I18n.get("aiAgentDesign.plan.caption"), palette);
        plan.getChildren().add(caption);
        for (TerminalAgentModels.AgentActivity step : steps) {
            boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
            Node mark;
            if (running) {
                mark = AgentDesignWidgets.label(AgentDesignWidgets.spinner(frame, reducedAnimations), palette.accent(), 11, true);
            } else {
                mark = tick(step);
            }
            StackPane markBox = new StackPane(mark);
            markBox.setMinSize(16, 16);
            markBox.setPrefSize(16, 16);
            Label text = AgentDesignWidgets.label(AgentInlineBlockLayout.oneLine(
                    step.type() == TerminalAgentModels.AgentActivityType.ACTION ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step)),
                running ? palette.text() : palette.muted(), 11.5, running);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(6, markBox, text);
            row.setAlignment(Pos.CENTER_LEFT);
            plan.getChildren().add(row);
        }
        if (view.running() && !view.awaitingInput()) {
            Label next = AgentDesignWidgets.label("○  " + I18n.get("aiAgentDesign.plan.next"), palette.muted(), 11, false);
            next.setOpacity(0.7);
            plan.getChildren().add(next);
        }
    }

    /** A tick in the step's colour; a step finished since the last look draws its tick. */
    private Node tick(TerminalAgentModels.AgentActivity step) {
        AgentInlineBlockLayout.Segment marker = AgentInlineBlockLayout.marker(step, "");
        if (!"✓".equals(marker.text())) {
            return AgentDesignWidgets.label(marker.text(), palette.colorOf(marker.role()), 11, true);
        }
        Node known = ticks.get(step.id());
        if (known != null) {
            return known;
        }
        SVGPath tick = new SVGPath();
        tick.setContent(TICK);
        tick.setFill(null);
        tick.setStroke(palette.ok());
        tick.setStrokeWidth(2);
        ticks.put(step.id(), tick);
        if (!reducedAnimations) {
            tick.getStrokeDashArray().setAll(20.0, 20.0);
            tick.setStrokeDashOffset(20);
            Timeline draw = new Timeline(new KeyFrame(Duration.millis(320),
                new KeyValue(tick.strokeDashOffsetProperty(), 0, Interpolator.EASE_OUT)));
            draw.play();
        }
        return tick;
    }

    private void refreshExecution(List<TerminalAgentModels.AgentActivity> steps) {
        execution.getChildren().clear();
        TerminalAgentModels.Approval approval = view.trace().pendingApproval;
        if (approval != null) {
            execution.getChildren().add(AgentDesignWidgets.label("? " + AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)),
                palette.warn(), 11.5, true));
            execution.getChildren().add(AgentDesignWidgets.mono("$ " + AgentDesignWidgets.approvalCommands(approval), palette.text(), 11.5, true));
            HBox buttons = new HBox(6);
            buttons.getChildren().addAll(AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval));
            buttons.setPadding(new Insets(4, 0, 0, 0));
            execution.getChildren().add(buttons);
            return;
        }
        TerminalAgentModels.AgentActivity command = null;
        for (int i = steps.size() - 1; i >= 0 && command == null; i--) {
            if (steps.get(i).type() == TerminalAgentModels.AgentActivityType.ACTION) {
                command = steps.get(i);
            }
        }
        if (!view.running()) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                Label answerLabel = AgentDesignWidgets.label("◆ " + answer.strip(), palette.text(), 11.5, false);
                answerLabel.setWrapText(true);
                execution.getChildren().add(answerLabel);
                return;
            }
        }
        if (command == null) {
            execution.getChildren().add(AgentDesignWidgets.label(AgentDesignPowerlineBar.currentStepText(view), palette.muted(), 11.5, false));
            return;
        }
        boolean running = command.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
        execution.getChildren().add(AgentDesignWidgets.mono("$ " + AgentInlineBlockLayout.oneLine(AgentStepDetails.command(command)),
            palette.accent(), 11.5, true));
        if (running) {
            execution.getChildren().add(AgentDesignWidgets.mono(AgentDesignWidgets.spinner(frame, reducedAnimations) + " "
                + I18n.get("aiAgentDesign.plan.running"), palette.muted(), 11, false));
            return;
        }
        Integer exit = AgentStepDetails.exitCode(command);
        String elapsed = command.elapsedSeconds() > 0 ? " · " + I18n.get("ai.agent.activity.elapsed", command.elapsedSeconds()) : "";
        if (exit != null) {
            execution.getChildren().add(AgentDesignWidgets.mono(I18n.get("aiAgentDesign.exit", exit) + elapsed,
                exit == 0 ? palette.ok() : palette.error(), 11, false));
        }
        List<String> output = AgentStepDetails.output(command);
        for (String line : output.subList(Math.max(0, output.size() - OUTPUT_LINES), output.size())) {
            execution.getChildren().add(AgentDesignWidgets.mono(line, palette.text(), 11, false));
        }
    }
}
