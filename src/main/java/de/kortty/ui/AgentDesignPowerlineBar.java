package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 01: one status line in powerline style below the terminal: the agent, the step it
 * works on with a spinner, how many steps it took, tokens and time. ▴ unfolds the run's steps over
 * the lowest terminal lines; an open approval unfolds them by itself.
 */
final class AgentDesignPowerlineBar extends HBox implements AgentRunPresenter {

    static final double BAR_HEIGHT = 24.0;
    private static final double ARROW_WIDTH = 9.0;
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Label title = segmentLabel("◆ " + I18n.get("aiAgentDesign.inline.title"), true);
    private final Label step = segmentLabel("", false);
    private final Label count = segmentLabel("", false);
    private final StackPane titleArrow = arrow();
    private final StackPane stepArrow = arrow();
    private final StackPane countArrow = arrow();
    private final Label meta = new Label();
    private final Button pauseButton = AgentDesignTopBar.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"));
    private final Button cancelButton = AgentDesignTopBar.iconButton("■", I18n.get("dialog.cancel"));
    private final Button feedButton = AgentDesignTopBar.iconButton("▴", I18n.get("aiAgentDesign.steps.show"));
    private final Button hideButton = AgentDesignTopBar.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"));
    private final Button menuButton = AgentDesignTopBar.iconButton("⋯", I18n.get("aiAgentDesign.menu.more"));
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList feed = new AgentDesignStepList();
    private final Rectangle feedClip = new Rectangle();
    private final DoubleProperty feedReveal = new SimpleDoubleProperty(1.0);
    private final Timeline spinner;
    private Supplier<Pane> overlay = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean feedOpen;
    private boolean feedOpenedForInput;
    private View view;
    private int frame;

    AgentDesignPowerlineBar(AgentRunPresenter.Actions actions) {
        getStyleClass().add("ai-agent-design-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);
        step.setTextOverrun(OverrunStyle.ELLIPSIS);
        step.setMinWidth(60);
        step.setMaxWidth(420);
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        meta.getStyleClass().add("ai-agent-design-meta");
        meta.setMinWidth(Region.USE_PREF_SIZE);
        meta.setPadding(new Insets(0, 6, 0, 6));
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        feedButton.setOnAction(event -> setFeedOpen(!feedOpen, true));
        hideButton.setOnAction(event -> actions.hide());
        AgentDesignWidgets.wireRunMenuButton(menuButton, actions);
        HBox buttons = new HBox(3, pauseButton, cancelButton, feedButton, menuButton, hideButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(0, 4, 0, 0));
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(title, titleArrow, step, stepArrow, count, countArrow, fill, meta, buttons);

        feed.setMaxRows(8);
        feed.setManaged(false);
        feed.setClip(feedClip);
        feedReveal.addListener((obs, oldValue, newValue) -> clipFeed());
        spinner = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            frame++;
            refreshStep();
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            spinner.stop();
            removeFeed();
        });
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
    public void setOverlay(Supplier<Pane> overlay) {
        this.overlay = overlay != null ? overlay : () -> null;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        Color first = palette.accent();
        Color second = palette.accent().interpolate(palette.background(), 0.55);
        Color third = palette.accent().interpolate(palette.background(), 0.78);
        colorSegment(title, first, onColor(first));
        colorSegment(step, second, palette.text());
        colorSegment(count, third, palette.muted());
        colorArrow(titleArrow, first, second);
        colorArrow(stepArrow, second, third);
        colorArrow(countArrow, third, palette.background());
        feed.setPalette(palette);
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
        this.view = view;
        visibility.show();
        meta.setText(view.meta());
        // Counted like the block in the terminal: the commands the agent ran.
        count.setText(I18n.get("aiAgentDesign.inline.steps", view.trace().commandCount()));
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        if (view.running() && !view.paused() && !reducedAnimations) {
            if (spinner.getStatus() != Animation.Status.RUNNING) {
                spinner.play();
            }
        } else {
            spinner.stop();
        }
        if (view.awaitingInput() && !feedOpen) {
            feedOpenedForInput = true;
            setFeedOpen(true, true);
        } else if (!view.awaitingInput() && feedOpenedForInput) {
            feedOpenedForInput = false;
            setFeedOpen(false, false);
        }
        refreshStep();
        if (feedOpen) {
            showFeed(false);
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

    private void refreshStep() {
        if (view == null) {
            return;
        }
        String frameText = reducedAnimations || !view.running() ? "" : SPINNER[frame % SPINNER.length] + " ";
        step.setText(frameText + currentStepText(view));
        step.setTooltip(new Tooltip(currentStepText(view)));
        if (feedOpen && view.running()) {
            feed.showSteps(view.trace(), SPINNER[frame % SPINNER.length], System.currentTimeMillis());
        }
    }

    /** The step a status display names: an open question, else the running step, else the last one. */
    static String currentStepText(View view) {
        if (view.awaitingInput()) {
            return I18n.get("ai.agent.activity.inputRequired");
        }
        if (!view.running()) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                return AgentInlineBlockLayout.oneLine(answer);
            }
        }
        List<TerminalAgentModels.AgentActivity> steps = AgentInlineBlockLayout.listedSteps(view.trace());
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                return stepText(steps.get(i));
            }
        }
        return steps.isEmpty() ? I18n.get("ai.agent.activity.thinking") : stepText(steps.get(steps.size() - 1));
    }

    /** A step on one line: a shell command as the command itself, anything else by its text. */
    static String stepText(TerminalAgentModels.AgentActivity step) {
        return AgentInlineBlockLayout.oneLine(AgentStepDetails.isCommand(step)
            ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step));
    }

    private void setFeedOpen(boolean open, boolean animate) {
        feedOpen = open;
        feedButton.setText(open ? "▾" : "▴");
        feedButton.setTooltip(new Tooltip(I18n.get(open ? "aiAgentDesign.steps.hide" : "aiAgentDesign.steps.show")));
        if (open) {
            showFeed(animate);
        } else {
            removeFeed();
        }
    }

    /** Puts the step list over the lowest terminal lines, right above the bar. */
    private void showFeed(boolean animate) {
        Pane layer = overlay.get();
        if (layer == null || view == null || getScene() == null) {
            return;
        }
        feed.showSteps(view.trace(), SPINNER[frame % SPINNER.length], System.currentTimeMillis());
        if (feed.getParent() != layer) {
            removeFeed();
            layer.getChildren().add(feed);
        }
        feed.applyCss();
        Bounds bar = localToScene(getLayoutBounds());
        Point2D topLeft = layer.sceneToLocal(bar.getMinX(), bar.getMinY());
        double width = Math.min(bar.getWidth(), 760);
        double height = Math.min(feed.prefHeight(width), Math.max(60, layer.getHeight() * 0.6));
        feed.resizeRelocate(topLeft.getX(), topLeft.getY() - height, width, height);
        feedClip.setWidth(width);
        if (animate && !reducedAnimations) {
            Timeline reveal = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(feedReveal, 0.0)),
                new KeyFrame(Duration.millis(260), new KeyValue(feedReveal, 1.0, Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0))));
            reveal.play();
        } else {
            feedReveal.set(1.0);
        }
        clipFeed();
    }

    private void clipFeed() {
        double height = feed.getHeight();
        double shown = height * feedReveal.get();
        feedClip.setY(height - shown);
        feedClip.setHeight(shown);
        feedClip.setWidth(feed.getWidth());
    }

    private void removeFeed() {
        if (feed.getParent() instanceof Pane parent) {
            parent.getChildren().remove(feed);
        }
    }

    private static Label segmentLabel(String text, boolean bold) {
        Label label = new Label(text);
        label.setMinHeight(BAR_HEIGHT);
        label.setPrefHeight(BAR_HEIGHT);
        label.setPadding(new Insets(0, 8, 0, 8));
        label.setFont(Font.font("Monospaced", bold ? FontWeight.BOLD : FontWeight.NORMAL, 12));
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static StackPane arrow() {
        Polygon shape = new Polygon(0, 0, ARROW_WIDTH, BAR_HEIGHT / 2, 0, BAR_HEIGHT);
        StackPane arrow = new StackPane(shape);
        StackPane.setAlignment(shape, Pos.CENTER_LEFT);
        arrow.setMinSize(ARROW_WIDTH, BAR_HEIGHT);
        arrow.setPrefSize(ARROW_WIDTH, BAR_HEIGHT);
        arrow.setMaxSize(ARROW_WIDTH, BAR_HEIGHT);
        return arrow;
    }

    private static void colorSegment(Label label, Color background, Color text) {
        label.setBackground(new Background(new BackgroundFill(background, null, null)));
        label.setTextFill(text);
    }

    private static void colorArrow(StackPane arrow, Color from, Color to) {
        arrow.setBackground(new Background(new BackgroundFill(to, null, null)));
        ((Polygon) arrow.getChildren().get(0)).setFill(from);
    }

    /** Dark text on a bright accent, white text on a dark one. */
    static Color onColor(Color background) {
        return background.getBrightness() > 0.6 ? Color.web("#06121F") : Color.WHITE;
    }
}
