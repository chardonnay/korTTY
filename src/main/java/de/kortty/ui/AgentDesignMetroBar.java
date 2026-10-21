package de.kortty.ui;

import de.kortty.agentdesign.MetroStationModel;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
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

import java.util.ArrayList;
import java.util.List;

/**
 * KI-Agent design 08: a slim bar below the terminal that draws a run as a metro line, one station per
 * step. Finished stations are filled, the current one pulses, an open question glows amber, and a light
 * travels along the line as the run moves on. The bar fades out a few seconds after the last run ends.
 */
final class AgentDesignMetroBar extends HBox implements AgentRunPresenter {

    static final double BAR_HEIGHT = 52.0;
    private static final double STATION_SPACING = 58.0;
    private static final double STATION_RADIUS = 6.0;
    private static final double RAIL_Y = 18.0;

    /** Colours of the line, from the agent palette of the terminal's theme. */
    record Colors(Color accent, Color rail, Color muted, Color ok, Color warn, Color error, Color background) {

        static Colors of(AgentDesignPalette palette) {
            return new Colors(
                palette.accent(),
                palette.border().interpolate(palette.accent(), 0.15),
                palette.muted(),
                palette.ok(),
                palette.warn(),
                palette.error(),
                palette.background());
        }
    }

    private final Label title = new Label(I18n.get("aiAgentDesign.inline.title"));
    private final Label runCount = new Label();
    private final Label prompt = new Label();
    private final Region busyDot = new Region();
    private final StationLine line = new StationLine();
    private final Label meta = new Label();
    private final Button pauseButton = iconButton("⏸", I18n.get("ai.agent.control.pauseRun"));
    private final Button cancelButton = iconButton("■", I18n.get("dialog.cancel"));
    private final Button hideButton = iconButton("✕", I18n.get("aiAgentDesign.metro.hide"));
    private final Button menuButton = iconButton("⋯", I18n.get("aiAgentDesign.menu.more"));
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final Timeline busyPulse;
    private boolean reducedAnimations;
    private Colors colors = Colors.of(AgentDesignPalette.of((de.kortty.model.Theme) null));

    AgentDesignMetroBar(AgentRunPresenter.Actions actions) {
        getStyleClass().add("ai-agent-design-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(0, 8, 0, 10));
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);

        busyDot.getStyleClass().add("ai-agent-header-busy-dot");
        busyDot.setMinSize(7, 7);
        busyDot.setPrefSize(7, 7);
        busyDot.setMaxSize(7, 7);
        title.getStyleClass().add("ai-agent-design-title");
        runCount.getStyleClass().add("ai-agent-design-chip");
        runCount.setVisible(false);
        runCount.setManaged(false);
        HBox titleRow = new HBox(6, busyDot, title, runCount);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        prompt.getStyleClass().add("ai-agent-design-meta");
        prompt.setMinWidth(0);
        prompt.setMaxWidth(170);
        VBox left = new VBox(2, titleRow, prompt);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(110);
        left.setPrefWidth(170);
        left.setMaxWidth(170);

        HBox.setHgrow(line, Priority.ALWAYS);
        line.setMinWidth(80);

        meta.getStyleClass().add("ai-agent-design-meta");
        meta.setMinWidth(Region.USE_PREF_SIZE);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        AgentDesignWidgets.wireRunMenuButton(menuButton, actions);
        HBox right = new HBox(5, meta, pauseButton, cancelButton, menuButton, hideButton);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(left, line, right);

        busyPulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(busyDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(800), new KeyValue(busyDot.opacityProperty(), 0.35, Interpolator.EASE_BOTH)));
        busyPulse.setAutoReverse(true);
        busyPulse.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            busyPulse.stop();
            line.stopAnimations();
        });
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
    public void applyPalette(AgentDesignPalette palette) {
        this.colors = Colors.of(palette);
        line.requestLayout();
        line.redraw();
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        line.redraw();
    }

    @Override
    public void show(View view) {
        show(view.prompt(), view.stations(), view.meta(), view.running(), view.paused(), view.activeRuns());
    }

    /** Shows the run the bar follows. */
    void show(String promptText, MetroStationModel model, String metaText, boolean running, boolean paused,
              int activeRuns) {
        visibility.show();
        prompt.setText(promptText);
        prompt.setTooltip(promptText == null || promptText.isBlank() ? null : new Tooltip(promptText));
        meta.setText(metaText);
        runCount.setText("+" + Math.max(0, activeRuns - 1));
        runCount.setVisible(activeRuns > 1);
        runCount.setManaged(activeRuns > 1);
        pauseButton.setDisable(!running);
        pauseButton.setText(paused ? "▶" : "⏸");
        pauseButton.setTooltip(new Tooltip(I18n.get(paused ? "ai.agent.control.resumeRun" : "ai.agent.control.pauseRun")));
        cancelButton.setDisable(!running);
        busyDot.setVisible(running);
        if (running && !paused && !reducedAnimations) {
            if (busyPulse.getStatus() != Animation.Status.RUNNING) {
                busyPulse.playFromStart();
            }
        } else {
            busyPulse.stop();
            busyDot.setOpacity(1.0);
        }
        line.setModel(model);
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

    private static Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().addAll("ai-agent-design-button", "ai-agent-design-icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /** The rail with its stations; lays them out to the width it gets. */
    private final class StationLine extends Pane {
        private final Line rail = new Line();
        private final Line progress = new Line();
        private final Circle light = new Circle(4.0);
        private final List<Circle> dots = new ArrayList<>();
        private final List<Label> labels = new ArrayList<>();
        private final Timeline lightMove = new Timeline();
        private ScaleTransition pulse;
        private MetroStationModel model;
        private double lightX = -1;

        StationLine() {
            setMinHeight(BAR_HEIGHT);
            setPrefHeight(BAR_HEIGHT);
            rail.setStrokeWidth(2.0);
            progress.setStrokeWidth(2.0);
            light.setMouseTransparent(true);
            getChildren().addAll(rail, progress, light);
        }

        void setModel(MetroStationModel model) {
            this.model = model;
            redraw();
        }

        void redraw() {
            requestLayout();
        }

        void stopAnimations() {
            if (pulse != null) {
                pulse.stop();
            }
            lightMove.stop();
        }

        @Override
        protected void layoutChildren() {
            List<MetroStationModel.Station> stations = model != null ? model.stations() : List.of();
            int currentIndex = model != null ? model.currentIndex() : 0;
            double width = getWidth();
            double margin = 22.0;
            double usable = Math.max(0.0, width - 2 * margin);
            int maxStations = Math.max(2, (int) Math.floor(usable / STATION_SPACING) + 1);
            List<MetroStationModel.Station> shown = stations;
            int hidden = 0;
            if (stations.size() > maxStations) {
                hidden = stations.size() - (maxStations - 1);
                shown = new ArrayList<>(stations.subList(hidden, stations.size()));
                shown.add(0, new MetroStationModel.Station("more", MetroStationModel.StationKind.COMMAND,
                    "+" + hidden, "", MetroStationModel.StationState.DONE));
                currentIndex = Math.max(1, currentIndex - hidden + 1);
            }
            ensureNodes(shown.size());
            double step = shown.size() > 1 ? Math.min(STATION_SPACING * 1.6, usable / (shown.size() - 1)) : 0.0;
            double startX = margin;
            double endX = startX + step * Math.max(0, shown.size() - 1);
            rail.setStartX(startX);
            rail.setEndX(Math.max(startX, endX));
            rail.setStartY(RAIL_Y);
            rail.setEndY(RAIL_Y);
            rail.setStroke(colors.rail());
            double currentX = startX + step * Math.min(currentIndex, Math.max(0, shown.size() - 1));
            progress.setStartX(startX);
            progress.setEndX(currentX);
            progress.setStartY(RAIL_Y);
            progress.setEndY(RAIL_Y);
            progress.setStroke(colors.accent());
            Circle currentDot = null;
            for (int i = 0; i < shown.size(); i++) {
                MetroStationModel.Station station = shown.get(i);
                Circle dot = dots.get(i);
                Label label = labels.get(i);
                double x = startX + step * i;
                dot.setCenterX(x);
                dot.setCenterY(RAIL_Y);
                styleDot(dot, station.state());
                label.setText(labelFor(station));
                label.getStyleClass().removeAll("ai-agent-design-station-current", "ai-agent-design-attention");
                if (i == currentIndex) {
                    label.getStyleClass().add(station.state() == MetroStationModel.StationState.ATTENTION
                        ? "ai-agent-design-attention" : "ai-agent-design-station-current");
                    currentDot = dot;
                }
                Tooltip.install(dot, station.detail().isBlank() ? null : new Tooltip(station.detail()));
                double labelWidth = Math.min(STATION_SPACING + 8, label.prefWidth(-1));
                label.resizeRelocate(x - labelWidth / 2, RAIL_Y + 9, labelWidth, label.prefHeight(labelWidth));
            }
            for (int i = shown.size(); i < dots.size(); i++) {
                dots.get(i).setVisible(false);
                labels.get(i).setVisible(false);
            }
            for (int i = 0; i < shown.size(); i++) {
                dots.get(i).setVisible(true);
                labels.get(i).setVisible(true);
            }
            updatePulse(currentDot, currentIndex < shown.size() ? shown.get(currentIndex).state() : null);
            moveLight(currentX, model != null && !model.isFinished());
        }

        private void ensureNodes(int count) {
            while (dots.size() < count) {
                Circle dot = new Circle(STATION_RADIUS);
                dot.setStrokeWidth(2.0);
                Label label = new Label();
                label.getStyleClass().add("ai-agent-design-station");
                label.setMinWidth(0);
                dots.add(dot);
                labels.add(label);
                getChildren().add(getChildren().indexOf(light), dot);
                getChildren().add(label);
            }
        }

        private void styleDot(Circle dot, MetroStationModel.StationState state) {
            Color fill = colors.background();
            Color stroke = colors.rail();
            switch (state) {
                case DONE -> {
                    fill = colors.accent();
                    stroke = colors.accent();
                }
                case ACTIVE -> stroke = colors.accent();
                case ATTENTION -> stroke = colors.warn();
                case FAILED -> {
                    fill = colors.error();
                    stroke = colors.error();
                }
                case CANCELLED -> stroke = colors.muted();
                case PENDING -> stroke = colors.rail();
            }
            dot.setFill(fill);
            dot.setStroke(stroke);
            dot.setEffect(state == MetroStationModel.StationState.ATTENTION
                ? new DropShadow(10, colors.warn())
                : null);
        }

        private String labelFor(MetroStationModel.Station station) {
            return switch (station.kind()) {
                case COMMAND -> station.label();
                case ANALYSIS -> I18n.get("aiAgentDesign.metro.analysis");
                case QUESTION -> I18n.get("aiAgentDesign.metro.question");
                case END -> I18n.get("aiAgentDesign.metro.answer");
            };
        }

        private void updatePulse(Circle dot, MetroStationModel.StationState state) {
            boolean animate = dot != null && !reducedAnimations
                && (state == MetroStationModel.StationState.ACTIVE || state == MetroStationModel.StationState.ATTENTION);
            if (pulse != null && (pulse.getNode() != dot || !animate)) {
                pulse.stop();
                pulse.getNode().setScaleX(1.0);
                pulse.getNode().setScaleY(1.0);
                pulse = null;
            }
            if (animate && pulse == null) {
                pulse = new ScaleTransition(Duration.millis(700), dot);
                pulse.setFromX(1.0);
                pulse.setFromY(1.0);
                pulse.setToX(1.35);
                pulse.setToY(1.35);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.setInterpolator(Interpolator.EASE_BOTH);
                pulse.play();
            }
        }

        private void moveLight(double targetX, boolean running) {
            light.setVisible(running);
            light.setCenterY(RAIL_Y);
            light.setFill(Color.WHITE.interpolate(colors.accent(), 0.25));
            light.setEffect(new DropShadow(9, colors.accent()));
            if (lightX < 0 || reducedAnimations || !running) {
                lightMove.stop();
                light.setCenterX(targetX);
                lightX = targetX;
                return;
            }
            if (Math.abs(targetX - lightX) < 0.5) {
                return;
            }
            lightMove.stop();
            lightMove.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(light.centerXProperty(), light.getCenterX())),
                new KeyFrame(Duration.millis(650), new KeyValue(light.centerXProperty(), targetX,
                    Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0))));
            lightMove.playFromStart();
            lightX = targetX;
        }
    }
}
