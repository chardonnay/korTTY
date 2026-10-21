package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KI-Agent design 02, the timeline at the side: a column right of the terminal with a vertical line and
 * one node per step: finished steps in blue, thinking in violet, an open approval in amber with its
 * buttons right at the node. The terminal keeps its full height; wide screens give it the room. The line
 * draws itself to each new node, the running node pulses, new entries slide in. FX thread.
 */
final class AgentDesignTimeline extends VBox implements AgentRunPresenter {

    static final double WIDTH = 240;
    private static final double NODE_RADIUS = 5;
    private static final double RAIL_WIDTH = 18;

    private final AgentRunPresenter.Actions actions;
    private final Label title = new Label("◆ " + I18n.get("aiAgentDesign.inline.title"));
    private final Label meta = new Label();
    private final Label prompt = new Label();
    private final Button pauseButton;
    private final Button cancelButton;
    private final Button hideButton;
    private final VBox entries = new VBox();
    private final ScrollPane scroller = new ScrollPane(entries);
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final Map<String, Entry> shown = new LinkedHashMap<>();
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private String runId;
    private TerminalAgentModels.Approval shownApproval;

    /** One node of the line with its text. */
    private final class Entry extends HBox {
        private final Circle node = new Circle(NODE_RADIUS);
        private final Line link = new Line();
        private final Label text = new Label();
        private final Label detail = new Label();
        private final VBox body = new VBox(1, text, detail);

        Entry() {
            setSpacing(6);
            setAlignment(Pos.TOP_LEFT);
            Pane rail = new Pane(link, node);
            rail.setMinWidth(RAIL_WIDTH);
            rail.setPrefWidth(RAIL_WIDTH);
            rail.setMinHeight(NODE_RADIUS * 2 + 6);
            rail.setPrefHeight(NODE_RADIUS * 2 + 6);
            // The link reaches down to the next node; it must not make the rail (and the entry) taller.
            link.setManaged(false);
            node.setCenterX(RAIL_WIDTH / 2);
            node.setCenterY(NODE_RADIUS + 3);
            link.setStartX(RAIL_WIDTH / 2);
            link.setEndX(RAIL_WIDTH / 2);
            link.setStartY(NODE_RADIUS * 2 + 4);
            link.endYProperty().bind(rail.heightProperty().add(2));
            link.setStrokeWidth(1.6);
            text.setWrapText(true);
            text.setMaxWidth(WIDTH - RAIL_WIDTH - 24);
            detail.setWrapText(true);
            detail.setMaxWidth(WIDTH - RAIL_WIDTH - 24);
            body.setPadding(new Insets(0, 0, 8, 0));
            HBox.setHgrow(body, Priority.ALWAYS);
            getChildren().addAll(rail, body);
        }

        void paint(Color color, String label, String sub, Color textColor, boolean mono) {
            node.setFill(color);
            node.setStroke(color.deriveColor(0, 1, 1.2, 1));
            link.setStroke(palette.border());
            text.setText(label);
            text.setTextFill(textColor);
            text.setFont(javafx.scene.text.Font.font(mono ? AgentDesignWidgets.MONOSPACED : javafx.scene.text.Font.getDefault().getFamily(), 11.5));
            detail.setText(sub);
            detail.setTextFill(palette.muted());
            detail.setVisible(sub != null && !sub.isBlank());
            detail.setManaged(detail.isVisible());
        }
    }

    AgentDesignTimeline(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        pauseButton = AgentDesignWidgets.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"), palette);
        cancelButton = AgentDesignWidgets.iconButton("■", I18n.get("dialog.cancel"), palette);
        hideButton = AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"), palette);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(4, title, gap, pauseButton, cancelButton,
            AgentDesignWidgets.runMenuButton(actions, palette), hideButton);
        header.setAlignment(Pos.CENTER_LEFT);
        prompt.setWrapText(true);
        prompt.setMaxHeight(36);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        entries.setPadding(new Insets(6, 4, 6, 0));
        VBox.setVgrow(scroller, Priority.ALWAYS);
        getChildren().addAll(header, meta, prompt, scroller);
        setSpacing(4);
        setPadding(new Insets(8, 8, 8, 10));
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        visibility.setOnHidden(() -> shown.values().forEach(entry -> setPulsing(entry, false)));
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
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 0 0 0 1;");
        title.setTextFill(palette.accent());
        title.setStyle("-fx-font-weight: bold;");
        meta.setTextFill(palette.muted());
        meta.setStyle("-fx-font-size: 0.8077em;");
        prompt.setTextFill(palette.text());
        scroller.setStyle(AgentDesignWidgets.scrollerStyle(palette));
        shown.clear();
        entries.getChildren().clear();
        shownApproval = null;
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            runId = view.runId();
            shown.clear();
            entries.getChildren().clear();
            shownApproval = null;
        }
        visibility.show();
        meta.setText(view.meta());
        prompt.setText(view.prompt());
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        List<TerminalAgentModels.AgentActivity> steps = AgentInlineBlockLayout.listedSteps(view.trace());
        Set<String> keys = new HashSet<>();
        boolean added = false;
        for (TerminalAgentModels.AgentActivity step : steps) {
            keys.add(step.id());
            Entry entry = shown.get(step.id());
            if (entry == null) {
                entry = new Entry();
                shown.put(step.id(), entry);
                entries.getChildren().add(entry);
                appear(entry);
                added = true;
            }
            boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
            boolean command = AgentStepDetails.isCommand(step);
            String text = AgentInlineBlockLayout.oneLine(command ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step));
            String sub = running ? AgentInlineBlockLayout.rowMeta(view.trace(), step, System.currentTimeMillis())
                : AgentStepDetails.result(step);
            entry.paint(colorFor(step), text, sub, palette.text(), command);
            AgentDesignWidgets.stepMenuOnRightClick(entry, actions, step);
            setPulsing(entry, running && view.running() && !view.paused());
        }
        for (String key : List.copyOf(shown.keySet())) {
            if (!keys.contains(key) && !key.startsWith("#")) {
                entries.getChildren().remove(shown.remove(key));
            }
        }
        added |= showApproval(view.trace().pendingApproval);
        added |= showAnswer(view);
        Entry last = null;
        for (Node child : entries.getChildren()) {
            if (child instanceof Entry entry) {
                entry.link.setVisible(true);
                last = entry;
            }
        }
        if (last != null) {
            last.link.setVisible(false);
        }
        if (added) {
            javafx.application.Platform.runLater(() -> scroller.setVvalue(1.0));
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

    private Color colorFor(TerminalAgentModels.AgentActivity step) {
        if (step.type() == TerminalAgentModels.AgentActivityType.ACTION
            && step.status() == TerminalAgentModels.AgentActivityStatus.COMPLETED) {
            Integer exit = AgentStepDetails.exitCode(step);
            return exit != null && exit != 0 ? palette.error() : palette.accent();
        }
        return AgentDesignWidgets.markerColor(palette, step);
    }

    /** The open approval as an amber node with its buttons; returns whether it was added. */
    private boolean showApproval(TerminalAgentModels.Approval approval) {
        if (approval == shownApproval) {
            return false;
        }
        shownApproval = approval;
        Entry old = shown.remove("#approval");
        if (old != null) {
            entries.getChildren().remove(old);
        }
        if (approval == null) {
            return false;
        }
        Entry entry = new Entry();
        entry.paint(palette.warn(), I18n.get("aiAgentDesign.toast.approval"), AgentDesignWidgets.approvalCommands(approval),
            palette.warn(), false);
        entry.detail.setFont(javafx.scene.text.Font.font(AgentDesignWidgets.MONOSPACED, 11));
        entry.detail.setTextFill(palette.text());
        HBox buttons = new HBox(4);
        buttons.getChildren().addAll(AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval));
        buttons.setPadding(new Insets(3, 0, 0, 0));
        entry.body.getChildren().add(buttons);
        shown.put("#approval", entry);
        entries.getChildren().add(entry);
        appear(entry);
        setPulsing(entry, !reducedAnimations);
        return true;
    }

    /** The answer at the end of the line once the run ended; returns whether it was added. */
    private boolean showAnswer(View view) {
        if (view.running() || shown.containsKey("#answer")) {
            return false;
        }
        String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
        if (answer.isBlank()) {
            return false;
        }
        Entry entry = new Entry();
        entry.paint(palette.ok(), answer.strip(), "", palette.text(), false);
        entry.node.setRadius(NODE_RADIUS + 1);
        shown.put("#answer", entry);
        entries.getChildren().add(entry);
        appear(entry);
        return true;
    }

    /** A new entry slides in while the line draws itself down to it. */
    private void appear(Entry entry) {
        if (reducedAnimations) {
            return;
        }
        entry.setOpacity(0.0);
        entry.setTranslateX(12);
        FadeTransition fade = new FadeTransition(Duration.millis(220), entry);
        fade.setToValue(1.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(260), entry);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0));
        new ParallelTransition(fade, slide).play();
        // The link above the new node: the previous entry's line draws downwards.
        int index = entries.getChildren().indexOf(entry);
        if (index > 0 && entries.getChildren().get(index - 1) instanceof Entry previous) {
            previous.link.getStrokeDashArray().setAll(200.0, 200.0);
            previous.link.setStrokeDashOffset(200);
            Timeline draw = new Timeline(new KeyFrame(Duration.millis(320),
                new KeyValue(previous.link.strokeDashOffsetProperty(), 0, Interpolator.EASE_OUT)));
            draw.setOnFinished(event -> previous.link.getStrokeDashArray().clear());
            draw.play();
        }
    }

    private void setPulsing(Entry entry, boolean pulsing) {
        Object running = entry.getProperties().get("pulse");
        if (pulsing && running == null && !reducedAnimations) {
            ScaleTransition beat = new ScaleTransition(Duration.millis(650), entry.node);
            beat.setFromX(1.0);
            beat.setFromY(1.0);
            beat.setToX(1.35);
            beat.setToY(1.35);
            beat.setAutoReverse(true);
            beat.setCycleCount(Animation.INDEFINITE);
            DropShadow glow = new DropShadow(8, (Color) entry.node.getFill());
            entry.node.setEffect(glow);
            beat.play();
            entry.getProperties().put("pulse", beat);
        } else if (!pulsing && running instanceof ScaleTransition beat) {
            beat.stop();
            entry.node.setScaleX(1.0);
            entry.node.setScaleY(1.0);
            entry.node.setEffect(null);
            entry.getProperties().remove("pulse");
        }
    }
}
