package de.kortty.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.Supplier;

/**
 * KI-Agent design 12: a slim bar above the terminal, out of the way of the prompt and the newest
 * output at the bottom. A shimmering bar shows that the agent works and names the current step; ▾
 * drops the run's steps down over the older output.
 */
final class AgentDesignTopBar extends HBox implements AgentStatusPresenter {

    static final double BAR_HEIGHT = 30.0;
    private static final double TRACK_HEIGHT = 4.0;

    private final Region busyDot = new Region();
    private final Label prompt = new Label();
    private final Region track = new Region();
    private final Region trackFill = new Region();
    private final Label step = new Label();
    private final Label meta = new Label();
    private final Button pauseButton = iconButton("⏸", I18n.get("ai.agent.control.pauseRun"));
    private final Button cancelButton = iconButton("■", I18n.get("dialog.cancel"));
    private final Button drawerButton = iconButton("▾", I18n.get("aiAgentDesign.steps.show"));
    private final Button hideButton = iconButton("✕", I18n.get("aiAgentDesign.metro.hide"));
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList drawer = new AgentDesignStepList();
    private final Rectangle drawerClip = new Rectangle();
    private final DoubleProperty drawerReveal = new SimpleDoubleProperty(1.0);
    private final DoubleProperty shimmer = new SimpleDoubleProperty(0.0);
    private final Timeline shimmerMove;
    private Supplier<Pane> overlay = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean drawerOpen;
    private View view;

    AgentDesignTopBar(AgentStatusPresenter.Actions actions) {
        getStyleClass().add("ai-agent-design-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(0, 6, 0, 10));
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);
        // The bar sits above the terminal, so its line belongs at the bottom.
        setStyle("-fx-border-width: 0 0 1 0;");

        busyDot.getStyleClass().add("ai-agent-header-busy-dot");
        busyDot.setMinSize(7, 7);
        busyDot.setPrefSize(7, 7);
        busyDot.setMaxSize(7, 7);
        prompt.getStyleClass().add("ai-agent-design-title");
        prompt.setTextOverrun(OverrunStyle.ELLIPSIS);
        prompt.setMinWidth(60);
        prompt.setMaxWidth(280);
        HBox left = new HBox(7, busyDot, prompt);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(80);

        track.setMinHeight(TRACK_HEIGHT);
        track.setPrefHeight(TRACK_HEIGHT);
        track.setMaxHeight(TRACK_HEIGHT);
        trackFill.setMinHeight(TRACK_HEIGHT);
        trackFill.setPrefHeight(TRACK_HEIGHT);
        trackFill.setMaxHeight(TRACK_HEIGHT);
        StackPane trackPane = new StackPane(track, trackFill);
        StackPane.setAlignment(trackFill, Pos.CENTER_LEFT);
        step.getStyleClass().add("ai-agent-design-meta");
        step.setTextOverrun(OverrunStyle.ELLIPSIS);
        step.setMinWidth(0);
        step.setMaxWidth(Double.MAX_VALUE);
        VBox center = new VBox(3, trackPane, step);
        center.setAlignment(Pos.CENTER);
        center.setMinWidth(120);
        center.setPrefWidth(280);
        center.setMaxWidth(420);
        HBox.setHgrow(center, Priority.ALWAYS);

        meta.getStyleClass().add("ai-agent-design-meta");
        meta.setMinWidth(Region.USE_PREF_SIZE);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        drawerButton.setOnAction(event -> setDrawerOpen(!drawerOpen, true));
        hideButton.setOnAction(event -> actions.hide());
        HBox right = new HBox(4, meta, pauseButton, cancelButton, drawerButton, hideButton);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        getChildren().addAll(left, center, spacer, right);

        drawer.setMaxRows(10);
        drawer.setManaged(false);
        drawer.setClip(drawerClip);
        drawerReveal.addListener((obs, oldValue, newValue) -> clipDrawer());
        shimmer.addListener((obs, oldValue, newValue) -> paintTrack());
        shimmerMove = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(shimmer, 0.0)),
            new KeyFrame(Duration.millis(1400), new KeyValue(shimmer, 1.0, Interpolator.LINEAR)));
        shimmerMove.setCycleCount(Animation.INDEFINITE);
        trackFill.prefWidthProperty().bind(track.widthProperty());
        visibility.setOnHidden(() -> {
            shimmerMove.stop();
            removeDrawer();
        });
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.TOP;
    }

    @Override
    public Region node() {
        return this;
    }

    @Override
    public void setOverlay(Supplier<Pane> overlay) {
        this.overlay = overlay != null ? overlay : () -> null;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        track.setBackground(new Background(new BackgroundFill(
            palette.border(), new CornerRadii(2), Insets.EMPTY)));
        drawer.setPalette(palette);
        paintTrack();
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            shimmerMove.stop();
            paintTrack();
        }
    }

    @Override
    public void show(View view) {
        this.view = view;
        visibility.show();
        prompt.setText(view.prompt());
        prompt.setTooltip(view.prompt().isBlank() ? null : new Tooltip(view.prompt()));
        String current = AgentDesignPowerlineBar.currentStepText(view);
        step.setText(view.trace().commandCount() + " · " + current);
        step.setTooltip(new Tooltip(current));
        step.setStyle(view.awaitingInput() ? "-fx-text-fill: " + AgentDesignPalette.hex(palette.warn()) + ";" : "");
        meta.setText(view.meta());
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        busyDot.setVisible(view.running());
        boolean shimmering = view.running() && !view.paused() && !reducedAnimations;
        if (shimmering && shimmerMove.getStatus() != Animation.Status.RUNNING) {
            shimmerMove.play();
        } else if (!shimmering) {
            shimmerMove.stop();
        }
        paintTrack();
        if (drawerOpen) {
            showDrawer(false);
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

    /** A moving highlight while the agent works, solid green or red once the run ended. */
    private void paintTrack() {
        Color base = palette.accent();
        if (view != null && !view.running()) {
            boolean failed = view.trace() != null && view.trace().finalPhase != null
                && view.trace().finalPhase != de.kortty.model.TerminalAgentModels.Phase.DONE;
            base = failed ? palette.error() : palette.ok();
            trackFill.setBackground(new Background(new BackgroundFill(base, new CornerRadii(2), Insets.EMPTY)));
            return;
        }
        if (view != null && view.awaitingInput()) {
            base = palette.warn();
        }
        if (reducedAnimations || view == null || view.paused()) {
            trackFill.setBackground(new Background(new BackgroundFill(base, new CornerRadii(2), Insets.EMPTY)));
            return;
        }
        double offset = shimmer.get();
        Color light = base.interpolate(Color.WHITE, 0.6);
        LinearGradient gradient = new LinearGradient(offset - 0.35, 0, offset + 0.35, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0.0, base), new Stop(0.5, light), new Stop(1.0, base));
        trackFill.setBackground(new Background(new BackgroundFill(gradient, new CornerRadii(2), Insets.EMPTY)));
    }

    private void setDrawerOpen(boolean open, boolean animate) {
        drawerOpen = open;
        drawerButton.setText(open ? "▴" : "▾");
        drawerButton.setTooltip(new Tooltip(I18n.get(open ? "aiAgentDesign.steps.hide" : "aiAgentDesign.steps.show")));
        if (open) {
            showDrawer(animate);
        } else {
            removeDrawer();
        }
    }

    /** Drops the step list down below the bar, over the older terminal output. */
    private void showDrawer(boolean animate) {
        Pane layer = overlay.get();
        if (layer == null || view == null || getScene() == null) {
            return;
        }
        drawer.showSteps(view.trace(), "", System.currentTimeMillis());
        if (drawer.getParent() != layer) {
            removeDrawer();
            layer.getChildren().add(drawer);
        }
        drawer.applyCss();
        Bounds bar = localToScene(getLayoutBounds());
        Point2D bottomLeft = layer.sceneToLocal(bar.getMinX(), bar.getMaxY());
        double width = Math.min(bar.getWidth(), 760);
        double height = Math.min(drawer.prefHeight(width), Math.max(60, layer.getHeight() * 0.6));
        drawer.resizeRelocate(bottomLeft.getX(), bottomLeft.getY(), width, height);
        if (animate && !reducedAnimations) {
            Timeline reveal = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(drawerReveal, 0.0)),
                new KeyFrame(Duration.millis(260), new KeyValue(drawerReveal, 1.0, Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0))));
            reveal.play();
        } else {
            drawerReveal.set(1.0);
        }
        clipDrawer();
    }

    private void clipDrawer() {
        drawerClip.setWidth(drawer.getWidth());
        drawerClip.setHeight(drawer.getHeight() * drawerReveal.get());
    }

    private void removeDrawer() {
        if (drawer.getParent() instanceof Pane parent) {
            parent.getChildren().remove(drawer);
        }
    }

    static Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().addAll("ai-agent-design-button", "ai-agent-design-icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }
}
