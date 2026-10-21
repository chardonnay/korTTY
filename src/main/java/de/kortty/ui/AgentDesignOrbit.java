package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 15, the orbit: a 72-pixel circle in the bottom-right corner, its ring filling with the
 * run's progress and dots circling it while the agent works, the step count and time in the middle, a
 * label with the current command beside it. A click lets the run's steps bloom out of the circle and
 * folds them back the same way. FX thread.
 */
final class AgentDesignOrbit extends HBox implements AgentRunPresenter {

    static final double SIZE = 72;
    private static final double RADIUS = SIZE / 2 - 5;

    private final Label command = new Label();
    private final Circle face = new Circle(SIZE / 2);
    private final Circle track = new Circle(RADIUS);
    private final Arc ring = new Arc(0, 0, RADIUS, RADIUS, 90, 0);
    private final Group dots = new Group();
    private final Label count = new Label();
    private final Label time = new Label();
    private final StackPane circle;
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList steps = new AgentDesignStepList();
    private final StackPane bloom = new StackPane(steps);
    private final Circle bloomClip = new Circle();
    private final RotateTransition orbiting;
    private Timeline ringFill;
    private Supplier<Pane> controls = () -> null;
    private Supplier<javafx.scene.Node> canvas = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean bloomOpen;
    private View view;

    AgentDesignOrbit(AgentRunPresenter.Actions actions) {
        setSpacing(8);
        setAlignment(Pos.CENTER_RIGHT);
        setPickOnBounds(false);
        track.setFill(null);
        track.setStrokeWidth(4);
        ring.setType(ArcType.OPEN);
        ring.setFill(null);
        ring.setStrokeWidth(4);
        ring.setStrokeLineCap(StrokeLineCap.ROUND);
        for (int i = 0; i < 3; i++) {
            Circle dot = new Circle(2.4 - i * 0.5);
            double angle = Math.toRadians(-90 - i * 22);
            dot.setCenterX(Math.cos(angle) * (RADIUS + 0.5));
            dot.setCenterY(Math.sin(angle) * (RADIUS + 0.5));
            dot.setOpacity(1.0 - i * 0.28);
            dots.getChildren().add(dot);
        }
        // Keeps the group's bounds centred, so it turns about the circle's middle.
        Circle anchor = new Circle(RADIUS + 3);
        anchor.setFill(null);
        anchor.setStroke(null);
        dots.getChildren().add(0, anchor);
        VBox center = new VBox(0, count, time);
        center.setAlignment(Pos.CENTER);
        circle = new StackPane(face, track, new Group(ring), dots, center);
        circle.setMinSize(SIZE, SIZE);
        circle.setPrefSize(SIZE, SIZE);
        circle.setMaxSize(SIZE, SIZE);
        circle.setCursor(javafx.scene.Cursor.HAND);
        circle.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                setBloomOpen(!bloomOpen);
            }
        });
        AgentDesignWidgets.runMenuOnRightClick(circle, actions);
        AgentDesignWidgets.runMenuOnRightClick(command, actions);
        command.setMaxWidth(240);
        command.setFont(javafx.scene.text.Font.font(AgentDesignWidgets.MONOSPACED, 11));
        command.setPadding(new Insets(3, 9, 3, 9));
        getChildren().addAll(command, circle);
        orbiting = new RotateTransition(Duration.seconds(1.6), dots);
        orbiting.setByAngle(360);
        orbiting.setInterpolator(Interpolator.LINEAR);
        orbiting.setCycleCount(Animation.INDEFINITE);
        steps.setMaxRows(12);
        bloom.setManaged(false);
        bloom.setClip(bloomClip);
        bloom.setPickOnBounds(false);
        visibility.setOnHidden(() -> {
            orbiting.stop();
            setBloomOpen(false);
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
        face.setFill(palette.background().deriveColor(0, 1, 1, 0.95));
        face.setStroke(palette.border());
        face.setEffect(AgentDesignWidgets.shadow(16, 0.4));
        track.setStroke(palette.border().deriveColor(0, 1, 1, 0.7));
        ring.setStroke(palette.accent());
        for (javafx.scene.Node node : dots.getChildren().subList(1, dots.getChildren().size())) {
            ((Circle) node).setFill(palette.think());
        }
        count.setTextFill(palette.text());
        count.setStyle("-fx-font-weight: bold; -fx-font-size: 1em;");
        time.setTextFill(palette.muted());
        time.setStyle("-fx-font-size: 0.7692em;");
        command.setTextFill(palette.text());
        command.setStyle(AgentDesignWidgets.cardStyle(palette, 0.92, 11));
        steps.setPalette(palette);
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            orbiting.stop();
        }
    }

    @Override
    public void show(View view) {
        this.view = view;
        Pane layer = controls.get();
        if (layer == null) {
            return;
        }
        visibility.show();
        List<TerminalAgentModels.AgentActivity> listed = AgentInlineBlockLayout.listedSteps(view.trace());
        long done = listed.stream().filter(s -> s.status() != TerminalAgentModels.AgentActivityStatus.RUNNING).count();
        count.setText(done + "/" + listed.size());
        String elapsed = view.meta();
        int dot = elapsed.lastIndexOf(" · ");
        time.setText(dot >= 0 ? elapsed.substring(dot + 3) : elapsed);
        String current = AgentDesignPowerlineBar.currentStepText(view);
        command.setText(view.awaitingInput() ? "? " + current : current);
        command.setTextFill(view.awaitingInput() ? palette.warn() : palette.text());
        double target = view.running() ? (listed.isEmpty() ? 0 : -360.0 * done / Math.max(1, listed.size())) : -360;
        if (ringFill != null) {
            ringFill.stop();
        }
        if (reducedAnimations) {
            ring.setLength(target);
        } else {
            ringFill = new Timeline(new KeyFrame(Duration.millis(400),
                new KeyValue(ring.lengthProperty(), target, Interpolator.EASE_BOTH)));
            ringFill.play();
        }
        ring.setStroke(view.running() ? palette.accent() : hasFailure(view) ? palette.error() : palette.ok());
        boolean orbit = view.running() && !view.paused() && !view.awaitingInput() && !reducedAnimations;
        dots.setVisible(view.running() && !view.awaitingInput());
        if (orbit && orbiting.getStatus() != Animation.Status.RUNNING) {
            orbiting.play();
        } else if (!orbit) {
            orbiting.pause();
        }
        AgentDesignWidgets.anchor(this, layer, Pos.BOTTOM_RIGHT, new Insets(0, 14, 12, 0), canvas);
        if (bloomOpen) {
            placeBloom(false);
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

    private static boolean hasFailure(View view) {
        TerminalAgentModels.Phase phase = view.trace().finalPhase;
        return phase == TerminalAgentModels.Phase.FAILED || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED;
    }

    private void setBloomOpen(boolean open) {
        if (open == bloomOpen && (open || bloom.getParent() == null)) {
            return;
        }
        bloomOpen = open;
        if (open) {
            placeBloom(true);
        } else if (bloom.getParent() != null) {
            if (reducedAnimations) {
                AgentDesignWidgets.detach(bloom);
                return;
            }
            Timeline fold = new Timeline(new KeyFrame(Duration.millis(260),
                new KeyValue(bloomClip.radiusProperty(), SIZE / 2, Interpolator.EASE_IN)));
            fold.setOnFinished(event -> {
                if (!bloomOpen) {
                    AgentDesignWidgets.detach(bloom);
                }
            });
            fold.play();
        }
    }

    /** The run's steps above and left of the circle, revealed by a circle growing out of it. */
    private void placeBloom(boolean animate) {
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null || view == null) {
            return;
        }
        steps.showSteps(view.trace(), "", System.currentTimeMillis());
        AgentDesignWidgets.attach(bloom, layer);
        steps.applyCss();
        double width = 320;
        double height = Math.min(steps.prefHeight(width), Math.max(80, getLayoutY() + getHeight() - 20));
        double circleX = getLayoutX() + getWidth() - SIZE / 2;
        double circleY = getLayoutY() + getHeight() - SIZE / 2;
        double x = Math.max(8, circleX - width + SIZE / 2);
        double y = Math.max(8, circleY - SIZE / 2 - height - 8);
        bloom.resizeRelocate(x, y, width, height);
        bloomClip.setCenterX(circleX - x);
        bloomClip.setCenterY(circleY - y);
        double reach = Math.hypot(Math.max(circleX - x, x + width - circleX), Math.max(circleY - y, y + height - circleY));
        if (animate && !reducedAnimations) {
            bloomClip.setRadius(SIZE / 2);
            new Timeline(new KeyFrame(Duration.millis(340),
                new KeyValue(bloomClip.radiusProperty(), reach, Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0)))).play();
        } else {
            bloomClip.setRadius(reach);
        }
    }
}
