package de.kortty.ui;

import de.kortty.core.GuideTranslationJob;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Shows a running guide translation in the top-right of the menu bar: a small bar, the percentage
 * and the time left.
 *
 * <p>A translation runs for hours while the user keeps working, so it needs to be visible from
 * anywhere — not only in the settings window that happened to start it. Hidden entirely when
 * nothing is running, so it costs no space the rest of the time.
 *
 * <p>Repaints on a one-second tick as well as on job events: the remaining time keeps counting
 * down between batches, and on a local model a batch can take a minute.
 */
final class GuideTranslationIndicator {

    private static final double BAR_WIDTH = 70;
    private static final double BAR_HEIGHT = 8;

    private final HBox node = new HBox(6);
    // Drawn by hand rather than with a ProgressBar: the control's track is styled through a
    // sub-structure selector that an inline style cannot reach, so on the dark menu bar it came
    // out invisible — correctly sized at 70x8 and impossible to see.
    private final StackPane bar = new StackPane();
    private final Region barTrack = new Region();
    private final Region barFill = new Region();
    private final Label label = new Label();
    private final Tooltip tooltip = new Tooltip();
    private final GuideTranslationJob job = GuideTranslationJob.getInstance();
    private final Runnable listener = () -> Platform.runLater(this::refresh);
    private Timeline ticker;

    GuideTranslationIndicator() {
        bar.setPrefSize(BAR_WIDTH, BAR_HEIGHT);
        bar.setMinSize(BAR_WIDTH, BAR_HEIGHT);
        bar.setMaxSize(BAR_WIDTH, BAR_HEIGHT);
        bar.setAlignment(Pos.CENTER_LEFT);
        barTrack.setPrefSize(BAR_WIDTH, BAR_HEIGHT);
        barTrack.setStyle("-fx-background-color: #ffffff30; -fx-background-radius: 4;");
        barFill.setPrefHeight(BAR_HEIGHT);
        barFill.setMaxWidth(0);
        barFill.setMaxHeight(BAR_HEIGHT);
        barFill.setStyle("-fx-background-color: #4a9eff; -fx-background-radius: 4;");
        bar.getChildren().addAll(barTrack, barFill);
        label.setStyle("-fx-font-size: 11px;");
        node.setAlignment(Pos.CENTER_RIGHT);
        node.setPadding(new Insets(0, 10, 0, 10));
        node.getChildren().addAll(bar, label);
        node.getStyleClass().add("guide-translation-indicator");
        Tooltip.install(node, tooltip);
        setVisible(false);
        job.addListener(listener);
        refresh();
    }

    HBox getNode() {
        return node;
    }

    /** Stops observing; call when the window closes so the job does not pin a dead listener. */
    void dispose() {
        job.removeListener(listener);
        stopTicker();
    }

    private void refresh() {
        GuideTranslationJob.Snapshot snapshot = job.snapshot();
        if (!snapshot.running()) {
            setVisible(false);
            stopTicker();
            return;
        }
        startTicker();
        setVisible(true);
        // A sliver stays visible at 1%, so "started" never looks like "nothing happening".
        double fraction = Math.max(0, Math.min(1, snapshot.progress()));
        // The minimum applies from 0% too: the bar only exists while a run is going, and a
        // running job that draws nothing reads as "nothing is happening".
        double width = Math.max(3, BAR_WIDTH * fraction);
        // maxWidth as well as prefWidth: a StackPane stretches its children to its own size, so
        // the preferred width alone left the bar looking complete from the first update.
        barFill.setPrefWidth(width);
        barFill.setMaxWidth(width);
        String language = snapshot.language() != null
            ? Locale.forLanguageTag(snapshot.language()).getDisplayLanguage() : "";
        String percent = snapshot.percent() + "%";
        label.setText(snapshot.hasRemainingEstimate()
            ? percent + " · " + formatRemaining(snapshot.remainingMillis())
            : percent);
        tooltip.setText(I18n.get("guide.translation.indicator.tooltip", language, percent));
    }

    private void setVisible(boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    /** Coarse on purpose — a per-second countdown over five hours would be false precision. */
    static String formatRemaining(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 1) {
            return "<1 min";
        }
        if (minutes < 90) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }
}
