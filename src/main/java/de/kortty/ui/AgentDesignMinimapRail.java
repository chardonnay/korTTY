package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 11: a narrow rail at the right edge of the terminal with one coloured segment per step
 * of the run, newest at the bottom. The running step glows; pointing at a segment shows a lens with the
 * step, its result and the first lines of its detail.
 */
final class AgentDesignMinimapRail extends Pane implements AgentRunPresenter {

    static final double RAIL_WIDTH = 14.0;
    private static final double GAP = 3.0;
    private static final double MAX_SEGMENT = 28.0;
    private static final double MIN_SEGMENT = 5.0;

    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final List<Rectangle> segments = new ArrayList<>();
    private final AgentDesignStepList lens = new AgentDesignStepList();
    private final Timeline glowPulse;
    private final DropShadow glow = new DropShadow(8, Color.WHITE);
    private Supplier<Pane> overlay = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private View view;
    private List<TerminalAgentModels.AgentActivity> steps = List.of();

    private final AgentRunPresenter.Actions actions;

    AgentDesignMinimapRail(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        // A right-click on the rail opens the run menu, on a segment the menu of its step.
        AgentDesignWidgets.runMenuOnRightClick(this, actions);
        setMinWidth(RAIL_WIDTH);
        setPrefWidth(RAIL_WIDTH);
        setMaxWidth(RAIL_WIDTH);
        lens.setManaged(false);
        lens.setMaxRows(1);
        glowPulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 3.0)),
            new KeyFrame(Duration.millis(700), new KeyValue(glow.radiusProperty(), 12.0, Interpolator.EASE_BOTH)));
        glowPulse.setAutoReverse(true);
        glowPulse.setCycleCount(Animation.INDEFINITE);
        setOnMouseExited(event -> hideLens());
        visibility.setOnHidden(() -> {
            glowPulse.stop();
            hideLens();
        });
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
    public void setOverlay(Supplier<Pane> overlay) {
        this.overlay = overlay != null ? overlay : () -> null;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setBackground(new Background(new BackgroundFill(palette.background(), null, null)));
        setBorder(new Border(new BorderStroke(palette.border(), BorderStrokeStyle.SOLID, null, new BorderWidths(0, 0, 0, 1))));
        lens.setPalette(palette);
        glow.setColor(palette.accent());
        recolor();
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            glowPulse.stop();
        }
    }

    @Override
    public void show(View view) {
        this.view = view;
        visibility.show();
        List<TerminalAgentModels.AgentActivity> listed = AgentInlineBlockLayout.listedSteps(view.trace());
        int previousCount = segments.size();
        while (segments.size() < listed.size()) {
            Rectangle segment = new Rectangle(RAIL_WIDTH - 6, MIN_SEGMENT);
            segment.setArcWidth(3);
            segment.setArcHeight(3);
            int index = segments.size();
            segment.setOnMouseEntered(event -> showLens(index));
            segment.setOnContextMenuRequested(event -> {
                if (index < steps.size()) {
                    actions.showStepMenu(segment, steps.get(index), event.getScreenX(), event.getScreenY());
                }
                event.consume();
            });
            segments.add(segment);
            getChildren().add(segment);
        }
        while (segments.size() > listed.size()) {
            getChildren().remove(segments.remove(segments.size() - 1));
        }
        steps = listed;
        recolor();
        requestLayout();
        if (!reducedAnimations) {
            for (int i = previousCount; i < segments.size(); i++) {
                Rectangle segment = segments.get(i);
                segment.setScaleY(0.0);
                ScaleTransition grow = new ScaleTransition(Duration.millis(260), segment);
                grow.setToY(1.0);
                grow.setInterpolator(Interpolator.EASE_OUT);
                grow.play();
            }
        }
        boolean anyRunning = steps.stream().anyMatch(step -> step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING);
        if (anyRunning && view.running() && !reducedAnimations) {
            if (glowPulse.getStatus() != Animation.Status.RUNNING) {
                glowPulse.play();
            }
        } else {
            glowPulse.stop();
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

    @Override
    protected void layoutChildren() {
        double available = Math.max(0, getHeight() - 2 * GAP);
        int count = segments.size();
        double height = count == 0 ? 0 : Math.max(MIN_SEGMENT, Math.min(MAX_SEGMENT, available / count - GAP));
        double y = GAP;
        for (Rectangle segment : segments) {
            segment.setX(3);
            segment.setY(y);
            segment.setHeight(height);
            segment.setWidth(RAIL_WIDTH - 6);
            y += height + GAP;
        }
    }

    private void recolor() {
        for (int i = 0; i < segments.size() && i < steps.size(); i++) {
            TerminalAgentModels.AgentActivity step = steps.get(i);
            Rectangle segment = segments.get(i);
            segment.setFill(colorOf(step));
            boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
            segment.setEffect(running ? glow : null);
        }
    }

    /** Blue for commands, violet for analysis, amber for questions, red for errors. */
    private Color colorOf(TerminalAgentModels.AgentActivity step) {
        if (step.type() == TerminalAgentModels.AgentActivityType.ERROR
            || step.status() == TerminalAgentModels.AgentActivityStatus.FAILED) {
            return palette.error();
        }
        if (step.status() == TerminalAgentModels.AgentActivityStatus.CANCELLED) {
            return palette.muted();
        }
        return switch (step.type()) {
            case THINKING -> palette.think();
            case QUESTION -> palette.warn();
            case MESSAGE -> palette.text().deriveColor(0, 1, 1, 0.7);
            default -> palette.accent();
        };
    }

    /** The lens left of the rail, level with the segment, with the step and its detail. */
    private void showLens(int index) {
        Pane layer = overlay.get();
        if (layer == null || view == null || index >= steps.size() || getScene() == null) {
            return;
        }
        lens.showStep(view.trace(), steps.get(index), System.currentTimeMillis());
        if (lens.getParent() != layer) {
            hideLens();
            layer.getChildren().add(lens);
        }
        lens.applyCss();
        Rectangle segment = segments.get(index);
        Bounds bounds = segment.localToScene(segment.getBoundsInLocal());
        Bounds rail = localToScene(getLayoutBounds());
        Point2D at = layer.sceneToLocal(rail.getMinX(), bounds.getMinY());
        double width = 320;
        double height = lens.prefHeight(width);
        double y = Math.max(4, Math.min(at.getY() - 6, layer.getHeight() - height - 4));
        lens.resizeRelocate(at.getX() - width - 8, y, width, height);
        if (!reducedAnimations) {
            lens.setScaleX(0.85);
            lens.setScaleY(0.85);
            ScaleTransition pop = new ScaleTransition(Duration.millis(160), lens);
            pop.setToX(1.0);
            pop.setToY(1.0);
            pop.play();
        }
    }

    private void hideLens() {
        if (lens.getParent() instanceof Pane parent) {
            parent.getChildren().remove(lens);
        }
    }
}
