package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * KI-Agent design 10, notices at the edge: every finished step slides in as a short notice at the bottom
 * right of the terminal and goes again after a few seconds, a bar in each showing when; an approval
 * stays until it is answered, with its buttons. A small badge counts the steps and unfolds them all.
 * For running the agent on the side. FX thread.
 */
final class AgentDesignToasts extends VBox implements AgentRunPresenter {

    static final double WIDTH = 290;
    private static final Duration LIFETIME = Duration.seconds(4);
    private static final int MAX_NOTICES = 4;

    private final AgentRunPresenter.Actions actions;
    private final VBox notices = new VBox(6);
    private final HBox badge = new HBox(6);
    private final Label badgeSpinner = new Label();
    private final Label badgeText = new Label();
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList steps = new AgentDesignStepList();
    private final Set<String> announced = new HashSet<>();
    private final Timeline spinner;
    private Supplier<Pane> controls = () -> null;
    private Supplier<javafx.scene.Node> canvas = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean stepsOpen;
    private VBox approvalNotice;
    private TerminalAgentModels.Approval shownApproval;
    private String runId;
    private View view;
    private int frame;

    AgentDesignToasts(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        setSpacing(6);
        setAlignment(Pos.BOTTOM_RIGHT);
        setFillWidth(true);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setPickOnBounds(false);
        notices.setFillWidth(true);
        notices.setPickOnBounds(false);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(3, 10, 3, 10));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.getChildren().addAll(badgeSpinner, badgeText);
        badge.setCursor(javafx.scene.Cursor.HAND);
        badge.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                setStepsOpen(!stepsOpen);
            }
        });
        AgentDesignWidgets.runMenuOnRightClick(badge, actions);
        HBox badgeRow = new HBox(badge);
        badgeRow.setAlignment(Pos.CENTER_RIGHT);
        badgeRow.setPickOnBounds(false);
        getChildren().addAll(notices, badgeRow);
        // Notices come and go between the run's updates; the stack stays in its corner.
        notices.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>) change ->
            javafx.application.Platform.runLater(() -> AgentDesignWidgets.reanchor(this)));
        notices.heightProperty().addListener((obs, oldValue, newValue) -> AgentDesignWidgets.reanchor(this));
        steps.setMaxRows(12);
        steps.setManaged(false);
        spinner = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            frame++;
            refreshBadge();
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            spinner.stop();
            setStepsOpen(false);
            notices.getChildren().clear();
            approvalNotice = null;
            shownApproval = null;
            AgentDesignWidgets.release(this);
            AgentDesignWidgets.detach(this);
        });
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.OVERLAY;
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
    public void setControlLayer(Supplier<Pane> controls) {
        this.controls = controls != null ? controls : () -> null;
    }

    @Override
    public void setTerminalCanvas(Supplier<javafx.scene.Node> canvas) {
        this.canvas = canvas != null ? canvas : () -> null;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        badge.setStyle(AgentDesignWidgets.cardStyle(palette, 0.94, 12));
        badgeSpinner.setTextFill(palette.accent());
        badgeText.setTextFill(palette.text());
        steps.setPalette(palette);
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
            announced.clear();
            notices.getChildren().clear();
            approvalNotice = null;
            shownApproval = null;
        }
        this.view = view;
        Pane layer = controls.get();
        if (layer == null) {
            return;
        }
        visibility.show();
        announceNewSteps();
        showApproval(view.trace().pendingApproval);
        refreshBadge();
        AgentDesignWidgets.anchor(this, layer, Pos.BOTTOM_RIGHT, new Insets(0, 14, 12, 0), canvas);
        boolean animate = view.running() && !view.paused() && !reducedAnimations;
        if (animate && spinner.getStatus() != Animation.Status.RUNNING) {
            spinner.play();
        } else if (!animate) {
            spinner.stop();
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

    /** One notice per step that finished since the last look; running steps wait until they are done. */
    private void announceNewSteps() {
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(view.trace())) {
            boolean finished = step.status() != TerminalAgentModels.AgentActivityStatus.RUNNING;
            if (!finished || step.type() == TerminalAgentModels.AgentActivityType.QUESTION || !announced.add(step.id())) {
                continue;
            }
            addNotice(step);
        }
        if (!view.running() && announced.add("answer")) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                addNotice(null, "◆", palette.accent(), AgentInlineBlockLayout.oneLine(answer), "");
            }
        }
    }

    private void addNotice(TerminalAgentModels.AgentActivity step) {
        AgentInlineBlockLayout.Segment marker = AgentInlineBlockLayout.marker(step, "");
        String text = AgentStepDetails.isCommand(step) ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step);
        addNotice(step, marker.text(), palette.colorOf(marker.role()), AgentInlineBlockLayout.oneLine(text),
            AgentStepDetails.result(step));
    }

    private void addNotice(TerminalAgentModels.AgentActivity step, String marker, javafx.scene.paint.Color markerColor,
                           String text, String result) {
        Label markerLabel = AgentDesignWidgets.label(marker, markerColor, 12, true);
        markerLabel.setMinWidth(14);
        Label textLabel = AgentDesignWidgets.label(text, palette.text(), 11.5, false);
        if (step != null && AgentStepDetails.isCommand(step)) {
            textLabel.setFont(javafx.scene.text.Font.font(AgentDesignWidgets.MONOSPACED, 11.5));
        }
        textLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textLabel, Priority.ALWAYS);
        HBox top = new HBox(6, markerLabel, textLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox notice = new VBox(2, top);
        if (result != null && !result.isBlank()) {
            Label resultLabel = AgentDesignWidgets.label(result, palette.muted(), 10.5, false);
            resultLabel.setPadding(new Insets(0, 0, 0, 20));
            notice.getChildren().add(resultLabel);
        }
        Rectangle countdown = new Rectangle(WIDTH - 20, 2, palette.accent());
        countdown.setOpacity(0.7);
        notice.getChildren().add(countdown);
        notice.setPadding(new Insets(7, 10, 6, 10));
        notice.setStyle(AgentDesignWidgets.cardStyle(palette, 0.95, 8));
        if (step != null) {
            AgentDesignWidgets.stepMenuOnRightClick(notice, actions, step);
        }
        notice.setEffect(AgentDesignWidgets.shadow(12, 0.3));
        while (notices.getChildren().size() >= MAX_NOTICES) {
            notices.getChildren().remove(approvalNotice != null && notices.getChildren().get(0) == approvalNotice ? 1 : 0);
        }
        int index = approvalNotice != null ? Math.max(0, notices.getChildren().indexOf(approvalNotice)) : notices.getChildren().size();
        notices.getChildren().add(index, notice);
        if (reducedAnimations) {
            PauseTransition life = new PauseTransition(LIFETIME);
            life.setOnFinished(event -> notices.getChildren().remove(notice));
            life.play();
            return;
        }
        notice.setTranslateX(260);
        notice.setOpacity(0.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(260), notice);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0));
        FadeTransition appear = new FadeTransition(Duration.millis(200), notice);
        appear.setToValue(1.0);
        countdown.setScaleX(1.0);
        Timeline bar = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(countdown.scaleXProperty(), 1.0)),
            new KeyFrame(LIFETIME, new KeyValue(countdown.scaleXProperty(), 0.0, Interpolator.LINEAR)));
        FadeTransition vanish = new FadeTransition(Duration.millis(220), notice);
        vanish.setToValue(0.0);
        Timeline collapse = new Timeline(new KeyFrame(Duration.millis(160),
            new KeyValue(notice.maxHeightProperty(), 0.0, Interpolator.EASE_IN)));
        notice.setMaxHeight(Region.USE_PREF_SIZE);
        SequentialTransition life = new SequentialTransition(new ParallelTransition(slide, appear), bar, vanish,
            new PauseTransition(Duration.millis(1)));
        life.setOnFinished(event -> {
            notice.setMaxHeight(notice.getHeight());
            collapse.setOnFinished(done -> notices.getChildren().remove(notice));
            collapse.play();
        });
        life.play();
    }

    /** An open approval as a notice that stays, with its buttons. */
    private void showApproval(TerminalAgentModels.Approval approval) {
        if (approval == shownApproval) {
            return;
        }
        shownApproval = approval;
        if (approvalNotice != null) {
            notices.getChildren().remove(approvalNotice);
            approvalNotice = null;
        }
        if (approval == null) {
            return;
        }
        Label marker = AgentDesignWidgets.label("?", palette.warn(), 12, true);
        Label title = AgentDesignWidgets.label(I18n.get("aiAgentDesign.toast.approval"), palette.warn(), 11.5, true);
        HBox top = new HBox(6, marker, title);
        top.setAlignment(Pos.CENTER_LEFT);
        Label command = AgentDesignWidgets.mono(AgentDesignWidgets.approvalCommands(approval), palette.text(), 11, false);
        command.setPadding(new Insets(0, 0, 0, 20));
        List<javafx.scene.control.Button> buttons = AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval);
        javafx.scene.layout.FlowPane buttonRow = new javafx.scene.layout.FlowPane(6, 4);
        buttonRow.getChildren().addAll(buttons);
        buttonRow.setPadding(new Insets(4, 0, 0, 20));
        buttonRow.setPrefWrapLength(WIDTH - 40);
        command.setMaxWidth(WIDTH - 40);
        VBox notice = new VBox(3, top, command, buttonRow);
        notice.setPadding(new Insets(8, 10, 8, 10));
        notice.setStyle(AgentDesignWidgets.cardStyle(palette, 0.97, 8)
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.warn()) + ";");
        notice.setEffect(AgentDesignWidgets.shadow(14, 0.35));
        approvalNotice = notice;
        notices.getChildren().add(notice);
        if (!reducedAnimations) {
            notice.setTranslateX(260);
            TranslateTransition slide = new TranslateTransition(Duration.millis(260), notice);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0));
            slide.play();
        }
    }

    private void refreshBadge() {
        if (view == null) {
            return;
        }
        int count = AgentInlineBlockLayout.listedSteps(view.trace()).size();
        badgeSpinner.setText(view.running() ? AgentDesignWidgets.spinner(frame, reducedAnimations) : "✓");
        badgeText.setText(I18n.get("aiAgentDesign.inline.title") + " · " + I18n.get("aiAgentDesign.toast.steps", count));
        if (stepsOpen) {
            placeSteps();
        }
        AgentDesignWidgets.reanchor(this);
    }

    private void setStepsOpen(boolean open) {
        stepsOpen = open;
        if (open) {
            placeSteps();
        } else {
            AgentDesignWidgets.detach(steps);
        }
    }

    /** All of the run's steps above the notices. */
    private void placeSteps() {
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null || view == null) {
            return;
        }
        steps.showSteps(view.trace(), AgentDesignWidgets.spinner(frame, reducedAnimations), System.currentTimeMillis());
        AgentDesignWidgets.attach(steps, layer);
        steps.applyCss();
        double height = Math.min(steps.prefHeight(WIDTH), Math.max(60, getLayoutY() - 12));
        steps.resizeRelocate(getLayoutX(), getLayoutY() - height - 6, WIDTH, height);
    }
}
