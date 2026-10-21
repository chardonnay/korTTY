package de.kortty.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.Locale;

/**
 * KI-Agent design 18: a departure-board bar below the terminal in the neon look of korTTY's logo. Each
 * new message rolls into a small window, an open question glows amber and stays, and the LED pulses
 * while the agent works.
 */
final class AgentDesignNeonTicker extends HBox implements AgentRunPresenter {

    static final double BAR_HEIGHT = 28.0;

    private final Circle led = new Circle(4);
    private final Label title = new Label(I18n.get("aiAgentDesign.inline.title").toUpperCase(Locale.ROOT));
    private final Pane window = new Pane();
    private final Rectangle windowClip = new Rectangle();
    private final Label meta = new Label();
    private final Button pauseButton = AgentDesignTopBar.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"));
    private final Button cancelButton = AgentDesignTopBar.iconButton("■", I18n.get("dialog.cancel"));
    private final Button hideButton = AgentDesignTopBar.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"));
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final Timeline ledPulse;
    private Label current;
    private String currentMessage = "";
    private boolean currentAttention;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private Color glow = Color.web("#9FD0FF");
    private boolean reducedAnimations;
    private boolean flickered;

    AgentDesignNeonTicker(AgentRunPresenter.Actions actions) {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        setPadding(new Insets(0, 6, 0, 12));
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);
        title.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        title.setMinWidth(Region.USE_PREF_SIZE);
        window.setMinHeight(BAR_HEIGHT);
        window.setPrefHeight(BAR_HEIGHT);
        window.setMaxHeight(BAR_HEIGHT);
        window.setMinWidth(80);
        window.setClip(windowClip);
        windowClip.widthProperty().bind(window.widthProperty());
        windowClip.heightProperty().bind(window.heightProperty());
        HBox.setHgrow(window, Priority.ALWAYS);
        meta.setFont(Font.font("Monospaced", 11));
        meta.setMinWidth(Region.USE_PREF_SIZE);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        HBox buttons = new HBox(3, pauseButton, cancelButton, hideButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(led, title, window, meta, buttons);
        ledPulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(led.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(600), new KeyValue(led.opacityProperty(), 0.3, Interpolator.EASE_BOTH)));
        ledPulse.setAutoReverse(true);
        ledPulse.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            ledPulse.stop();
            flickered = false;
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
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        // The neon look needs a near-black board; on a light theme it keeps the theme's own panel.
        Color board = palette.dark() ? Color.web("#03070C") : palette.background();
        glow = palette.dark() ? palette.accent().interpolate(Color.WHITE, 0.45) : palette.accent().darker();
        setBackground(new Background(new BackgroundFill(board, null, null)));
        setBorder(new Border(new BorderStroke(palette.accent().deriveColor(0, 1, 1, 0.45), BorderStrokeStyle.SOLID,
            null, new BorderWidths(1, 0, 0, 0))));
        led.setFill(glow);
        led.setEffect(new DropShadow(BlurType.GAUSSIAN, palette.accent(), 10, 0.5, 0, 0));
        title.setTextFill(glow);
        title.setEffect(neon(palette.accent()));
        meta.setTextFill(palette.muted());
        if (current != null) {
            styleMessage(current, currentAttention);
        }
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            ledPulse.stop();
            led.setOpacity(1.0);
        }
    }

    @Override
    public void show(View view) {
        boolean wasShowing = visibility.isShowing();
        visibility.show();
        if (!wasShowing && !flickered && !reducedAnimations) {
            flickered = true;
            flicker();
        }
        meta.setText(view.meta().toUpperCase(Locale.ROOT));
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        if (view.running() && !view.paused() && !reducedAnimations) {
            if (ledPulse.getStatus() != Animation.Status.RUNNING) {
                ledPulse.play();
            }
        } else {
            ledPulse.stop();
            led.setOpacity(view.running() ? 1.0 : 0.4);
        }
        String message = message(view);
        if (!message.equals(currentMessage) || view.awaitingInput() != currentAttention) {
            roll(message, view.awaitingInput());
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

    /** The board's text: the open question with its keys, else the step, else the answer. */
    static String message(View view) {
        if (view.awaitingInput()) {
            return "? " + I18n.get("ai.agent.activity.inputRequired").toUpperCase(Locale.ROOT)
                + "  [1] [2] [3]";
        }
        String text = AgentDesignPowerlineBar.currentStepText(view);
        String prefix = view.running() ? "▸ " : "✓ ";
        return (prefix + text).toUpperCase(Locale.ROOT);
    }

    /** The old message rolls up and out while the new one rolls in from below. */
    private void roll(String message, boolean attention) {
        Label next = new Label(message);
        next.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        next.setMinWidth(Region.USE_PREF_SIZE);
        styleMessage(next, attention);
        next.setLayoutY((BAR_HEIGHT - 16) / 2);
        Label previous = current;
        current = next;
        currentMessage = message;
        currentAttention = attention;
        window.getChildren().add(next);
        if (previous == null || reducedAnimations) {
            if (previous != null) {
                window.getChildren().remove(previous);
            }
            return;
        }
        Duration time = Duration.millis(320);
        TranslateTransition out = new TranslateTransition(time, previous);
        out.setToY(-BAR_HEIGHT);
        FadeTransition fade = new FadeTransition(time, previous);
        fade.setToValue(0.0);
        next.setTranslateY(BAR_HEIGHT);
        TranslateTransition in = new TranslateTransition(time, next);
        in.setToY(0);
        ParallelTransition swap = new ParallelTransition(out, fade, in);
        swap.setInterpolator(Interpolator.SPLINE(0.6, 0.0, 0.3, 1.0));
        swap.setOnFinished(event -> window.getChildren().remove(previous));
        swap.play();
    }

    private void styleMessage(Label label, boolean attention) {
        Color color = attention ? palette.warn() : glow;
        label.setTextFill(color);
        label.setEffect(neon(attention ? palette.warn() : palette.accent()));
    }

    /** A soft glow around the letters, like neon tubes. */
    private static DropShadow neon(Color color) {
        return new DropShadow(BlurType.GAUSSIAN, color, 10, 0.35, 0, 0);
    }

    /** Switching on flickers briefly, like a neon sign. */
    private void flicker() {
        Timeline flicker = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(opacityProperty(), 0.0)),
            new KeyFrame(Duration.millis(90), new KeyValue(opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(140), new KeyValue(opacityProperty(), 0.25)),
            new KeyFrame(Duration.millis(210), new KeyValue(opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(280), new KeyValue(opacityProperty(), 0.55)),
            new KeyFrame(Duration.millis(360), new KeyValue(opacityProperty(), 1.0)));
        flicker.play();
    }
}
