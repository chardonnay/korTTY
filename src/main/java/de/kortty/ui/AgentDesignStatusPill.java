package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 05, the status pill: a small pill in the top-right corner of the pane with the step the
 * agent works on, how far it got and the time. A click unfolds the run's steps below it. Runs asked in
 * the Spotlight ({@link AgentDesignSpotlight}) shrink into it. FX thread.
 */
final class AgentDesignStatusPill extends HBox implements AgentRunPresenter {

    private static final double HEIGHT = 28;

    private final Label icon = new Label("◆");
    private final Label spin = new Label();
    private final Label step = new Label();
    private final Label progress = new Label();
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList steps = new AgentDesignStepList();
    private final Timeline spinner;
    private Supplier<Pane> controls = () -> null;
    private Supplier<javafx.scene.Node> canvas = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean stepsOpen;
    private View view;
    private int frame;

    AgentDesignStatusPill(AgentRunPresenter.Actions actions) {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new Insets(0, 12, 0, 10));
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        step.setMaxWidth(260);
        step.setMinWidth(0);
        step.setFont(javafx.scene.text.Font.font(AgentDesignWidgets.MONOSPACED, 11));
        progress.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(icon, spin, step, progress);
        setOnMouseClicked(event -> setStepsOpen(!stepsOpen));
        setCursor(javafx.scene.Cursor.HAND);
        steps.setMaxRows(10);
        steps.setManaged(false);
        spinner = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            frame++;
            refresh();
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            spinner.stop();
            setStepsOpen(false);
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
        icon.setTextFill(palette.accent());
        spin.setTextFill(palette.accent());
        step.setTextFill(palette.text());
        progress.setTextFill(palette.muted());
        steps.setPalette(palette);
        restyle();
    }

    private void restyle() {
        boolean waiting = view != null && view.awaitingInput();
        setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), 0.94) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(waiting ? palette.warn() : palette.border()) + ";"
            + " -fx-border-width: 1; -fx-background-radius: 14; -fx-border-radius: 14;");
        setEffect(AgentDesignWidgets.shadow(14, 0.35));
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
        boolean wasShowing = isShowing();
        this.view = view;
        Pane layer = controls.get();
        if (layer == null) {
            return;
        }
        visibility.show();
        restyle();
        refresh();
        AgentDesignWidgets.anchor(this, layer, Pos.TOP_RIGHT, new Insets(10, 12, 0, 0), canvas);
        if (!wasShowing && !reducedAnimations) {
            setOpacity(0.0);
            FadeTransition fade = new FadeTransition(Duration.millis(220), this);
            fade.setToValue(1.0);
            fade.play();
        }
        if (view.running() && !view.paused() && !reducedAnimations) {
            if (spinner.getStatus() != Animation.Status.RUNNING) {
                spinner.play();
            }
        } else {
            spinner.stop();
        }
        if (stepsOpen) {
            placeSteps();
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

    private void refresh() {
        if (view == null) {
            return;
        }
        spin.setText(view.running() ? AgentDesignWidgets.spinner(frame, reducedAnimations) : "✓");
        spin.setVisible(!view.awaitingInput());
        spin.setManaged(!view.awaitingInput());
        String text = AgentDesignPowerlineBar.currentStepText(view);
        step.setText(view.awaitingInput() ? "? " + text : text);
        step.setTextFill(view.awaitingInput() ? palette.warn() : palette.text());
        step.setTooltip(new Tooltip(view.prompt() + "\n" + text));
        List<TerminalAgentModels.AgentActivity> listed = AgentInlineBlockLayout.listedSteps(view.trace());
        long done = listed.stream().filter(s -> s.status() != TerminalAgentModels.AgentActivityStatus.RUNNING).count();
        progress.setText(done + "/" + listed.size() + " · " + view.meta());
        AgentDesignWidgets.reanchor(this);
        if (stepsOpen) {
            steps.showSteps(view.trace(), AgentDesignWidgets.spinner(frame, reducedAnimations), System.currentTimeMillis());
        }
    }

    private void setStepsOpen(boolean open) {
        stepsOpen = open;
        if (open) {
            placeSteps();
        } else {
            AgentDesignWidgets.detach(steps);
        }
    }

    /** The run's steps under the pill, in the control layer. */
    private void placeSteps() {
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null || view == null) {
            return;
        }
        steps.showSteps(view.trace(), AgentDesignWidgets.spinner(frame, reducedAnimations), System.currentTimeMillis());
        AgentDesignWidgets.attach(steps, layer);
        steps.applyCss();
        double width = Math.min(380, Math.max(240, getWidth()));
        double height = Math.min(steps.prefHeight(width), Math.max(60, layer.getHeight() * 0.6));
        Point2D corner = new Point2D(getLayoutX() + getWidth() - width, getLayoutY() + getHeight() + 6);
        steps.resizeRelocate(Math.max(8, corner.getX()), corner.getY(), width, height);
    }
}
