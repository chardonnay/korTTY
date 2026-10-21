package de.kortty.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.util.function.Supplier;

/**
 * The countdown ring of KI-Agent design 20: after the last run ended it sits in the pane's corner and
 * runs down while the dock waits to fade out, so the user sees how long the result stays. FX thread.
 */
final class AgentDesignCountdownRing extends StackPane {

    private static final double SIZE = 26;

    private final Supplier<Pane> layer;
    private final Supplier<javafx.scene.Node> canvas;
    private final Circle track = new Circle(SIZE / 2 - 2);
    private final Arc ring = new Arc(0, 0, SIZE / 2 - 2, SIZE / 2 - 2, 90, 360);
    private final Label seconds = new Label();
    private Timeline countdown;

    AgentDesignCountdownRing(Supplier<Pane> layer, Supplier<javafx.scene.Node> canvas) {
        this.layer = layer;
        this.canvas = canvas;
        track.setFill(null);
        track.setStrokeWidth(2.5);
        ring.setType(ArcType.OPEN);
        ring.setFill(null);
        ring.setStrokeWidth(2.5);
        ring.setStrokeLineCap(StrokeLineCap.ROUND);
        seconds.setStyle("-fx-font-size: 0.6923em;");
        getChildren().addAll(track, ring, seconds);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        setMouseTransparent(true);
        applyPalette(AgentDesignPalette.of((de.kortty.model.Theme) null));
    }

    void applyPalette(AgentDesignPalette palette) {
        track.setStroke(palette.border());
        ring.setStroke(palette.accent());
        seconds.setTextFill(palette.muted());
        setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), 0.85) + "; -fx-background-radius: 13;");
    }

    /** Runs the ring down over {@code total} seconds, then fades it out. */
    void start(int total) {
        Pane pane = layer.get();
        if (pane == null || total <= 0) {
            return;
        }
        if (countdown != null) {
            countdown.stop();
        }
        setOpacity(1.0);
        AgentDesignWidgets.anchor(this, pane, Pos.BOTTOM_RIGHT, new Insets(10), canvas);
        ring.setLength(360);
        countdown = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(ring.lengthProperty(), 360)),
            new KeyFrame(Duration.seconds(total), new KeyValue(ring.lengthProperty(), 0, Interpolator.LINEAR)));
        for (int second = 0; second <= total; second++) {
            int left = total - second;
            countdown.getKeyFrames().add(new KeyFrame(Duration.seconds(second), event -> seconds.setText(String.valueOf(left))));
        }
        FadeTransition fade = new FadeTransition(Duration.millis(250), this);
        fade.setToValue(0.0);
        SequentialTransition all = new SequentialTransition(countdown, fade);
        all.setOnFinished(event -> {
            AgentDesignWidgets.release(this);
            AgentDesignWidgets.detach(this);
        });
        all.play();
    }
}
