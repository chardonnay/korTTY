package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

/**
 * KI-Agent design 17, focus mode: what the agent does is written into the terminal as if it had typed
 * it ({@link AgentFocusLayout}), a glowing bar marks those lines, a light runs along under them while it
 * works, and the rest of the terminal dims, so the run is the one thing to look at. When the run ends the
 * terminal brightens again and the lines stay in the scrollback. FX thread.
 */
final class AgentDesignFocusMode extends AgentDesignInlineBlocks {

    private static final Duration TRACK = Duration.millis(66);
    private static final double DIM_OPACITY = 0.58;

    private final @Nullable AgentTerminalSurface surface;
    private final Region dimAbove = new Region();
    private final Region dimBelow = new Region();
    private final Rectangle bar = new Rectangle(3, 0);
    private final Scale barGrowth = new Scale(1, 0, 0, 0);
    private final Rectangle light = new Rectangle(0, 2);
    private final Pane effects = new Pane(dimAbove, dimBelow, bar, light);
    private final Timeline tracker = new Timeline(new KeyFrame(TRACK, event -> place()));
    private final FadeTransition dimFade = new FadeTransition(Duration.millis(320), effects);
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private @Nullable String followedRunId;
    private boolean active;
    private double lightPhase;

    AgentDesignFocusMode(@Nullable AgentTerminalSurface surface) {
        super(surface, AgentFocusLayout::render);
        this.surface = surface;
        effects.setManaged(false);
        effects.setMouseTransparent(true);
        effects.setPickOnBounds(false);
        // Placed by hand on every frame; the pane's own layout would shrink them to nothing.
        for (javafx.scene.Node part : effects.getChildren()) {
            part.setManaged(false);
        }
        bar.getTransforms().add(barGrowth);
        bar.setArcWidth(3);
        bar.setArcHeight(3);
        tracker.setCycleCount(Animation.INDEFINITE);
        applyPalette(palette);
    }

    void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        Background dim = new Background(new BackgroundFill(palette.background().deriveColor(0, 1, 1, DIM_OPACITY), null, null));
        dimAbove.setBackground(dim);
        dimBelow.setBackground(dim);
        bar.setFill(palette.accent());
        DropShadow glow = new DropShadow(10, palette.accent());
        glow.setSpread(0.35);
        bar.setEffect(glow);
    }

    @Override
    void setReducedAnimations(boolean reducedAnimations) {
        super.setReducedAnimations(reducedAnimations);
        this.reducedAnimations = reducedAnimations;
    }

    /** Follows {@code runId}: dims the terminal around its lines while it runs, brightens it again after. */
    void follow(String runId, boolean running) {
        followedRunId = runId;
        if (running && !active) {
            start();
        } else if (!running && active) {
            stop();
        }
    }

    @Override
    void dispose() {
        super.dispose();
        tracker.stop();
        AgentDesignWidgets.detach(effects);
        active = false;
    }

    private void start() {
        active = true;
        Pane layer = surface != null ? surface.overlayLayer() : null;
        if (layer == null) {
            return;
        }
        AgentDesignWidgets.attach(effects, layer);
        effects.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
        dimFade.stop();
        if (reducedAnimations) {
            effects.setOpacity(1.0);
            barGrowth.setY(1.0);
        } else {
            effects.setOpacity(0.0);
            dimFade.setFromValue(0.0);
            dimFade.setToValue(1.0);
            dimFade.setOnFinished(null);
            dimFade.playFromStart();
            barGrowth.setY(0.0);
            new Timeline(new KeyFrame(Duration.millis(380),
                new KeyValue(barGrowth.yProperty(), 1.0, Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0)))).play();
        }
        tracker.play();
        place();
    }

    private void stop() {
        active = false;
        // Brighten the terminal; the bar lingers a moment on the finished lines.
        PauseTransition linger = new PauseTransition(Duration.millis(reducedAnimations ? 0 : 900));
        linger.setOnFinished(event -> {
            dimFade.stop();
            dimFade.setFromValue(effects.getOpacity());
            dimFade.setToValue(0.0);
            dimFade.setOnFinished(done -> {
                if (!active) {
                    tracker.stop();
                    AgentDesignWidgets.detach(effects);
                }
            });
            dimFade.playFromStart();
        });
        linger.play();
    }

    /** Puts the dimming, the bar and the light around the followed run's lines, wherever they are now. */
    private void place() {
        Pane layer = effects.getParent() instanceof Pane parent ? parent : null;
        TerminalCellGeometry geometry = surface != null ? surface.cellGeometry() : null;
        Node canvas = surface != null ? surface.canvas() : null;
        int[] rows = followedRunId != null ? blockRows(followedRunId) : null;
        if (layer == null || geometry == null || canvas == null || canvas.getScene() == null) {
            effects.setVisible(false);
            return;
        }
        effects.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
        Bounds canvasBounds = layer.sceneToLocal(canvas.localToScene(canvas.getLayoutBounds()));
        if (canvasBounds == null) {
            effects.setVisible(false);
            return;
        }
        effects.setVisible(true);
        double left = canvasBounds.getMinX();
        double width = canvasBounds.getWidth();
        double top = canvasBounds.getMinY();
        double bottom = canvasBounds.getMaxY();
        if (rows == null) {
            // The lines are not on the screen (yet, or scrolled away): dim nothing but keep the layer ready.
            dimAbove.resizeRelocate(left, top, width, 0);
            dimBelow.resizeRelocate(left, bottom, width, 0);
            bar.setVisible(false);
            light.setVisible(false);
            return;
        }
        double cell = geometry.cellHeight();
        double blockTop = top + (rows[0] - geometry.scrollOrigin()) * cell;
        int lastRow = rows[0] + rows[1];
        Point cursor = surface.cursorCell();
        if (cursor != null && cursor.y >= lastRow) {
            // Keep the prompt line under the lines bright, so the user sees what they type.
            lastRow = cursor.y + 1;
        }
        double blockBottom = top + (lastRow - geometry.scrollOrigin()) * cell;
        double clampedTop = Math.max(top, Math.min(bottom, blockTop));
        double clampedBottom = Math.max(clampedTop, Math.min(bottom, blockBottom));
        dimAbove.resizeRelocate(left, top, width, clampedTop - top);
        dimBelow.resizeRelocate(left, clampedBottom, width, bottom - clampedBottom);
        double linesBottom = Math.min(bottom, top + (rows[0] + rows[1] - geometry.scrollOrigin()) * cell);
        bar.setVisible(linesBottom > clampedTop);
        bar.setX(left + geometry.insetX() + 2);
        bar.setY(clampedTop + 1);
        bar.setHeight(Math.max(0, linesBottom - clampedTop - 2));
        barGrowth.setPivotY(clampedTop);
        double textWidth = Math.min(width - geometry.insetX(), AgentInlineBlockLayout.blockWidth(geometry.columns()) * geometry.cellWidth());
        light.setVisible(active && linesBottom > clampedTop);
        light.setX(left + geometry.insetX());
        light.setY(linesBottom - 1);
        light.setWidth(Math.max(0, textWidth));
        lightPhase = reducedAnimations ? 0.5 : (lightPhase + 0.035) % 1.4;
        double center = lightPhase - 0.2;
        Color glow = palette.accent();
        light.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.TRANSPARENT),
            new Stop(Math.max(0, Math.min(1, center - 0.18)), Color.TRANSPARENT),
            new Stop(Math.max(0, Math.min(1, center)), glow),
            new Stop(Math.max(0, Math.min(1, center + 0.18)), Color.TRANSPARENT),
            new Stop(1, Color.TRANSPARENT)));
    }
}
