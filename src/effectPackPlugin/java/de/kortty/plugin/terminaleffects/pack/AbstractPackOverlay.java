package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Base class for all effect pack overlays: a mouse-transparent, unmanaged canvas driven by a
 * fixed-interval {@link Timeline}. Subclasses only implement
 * {@link #paintFrame(GraphicsContext, double, double, long, double, double)}.
 *
 * <p>The animation speed multiplier is read per frame and must scale phase advancement
 * (band positions, event intervals) — never the frame interval — so CPU load stays constant.</p>
 *
 * <p>When bound to a terminal container via {@link #bindToContainer(StackPane)}, the overlay
 * releases its canvas backing store (unbinds and shrinks to 0x0) while it is not visible in the
 * scene — e.g. in a background tab — and rebinds automatically when it becomes visible again.
 * A full-window canvas texture per hidden tab would otherwise exhaust the Prism texture pool
 * once several effect tabs are open (RTTexture allocation returns null and rendering fails).</p>
 */
abstract class AbstractPackOverlay extends Canvas {

    static final long DEFAULT_FRAME_INTERVAL_MILLIS = 35L;

    protected final Random random = new Random();
    private final Timeline timeline;
    private final DoubleSupplier animationSpeedSupplier;
    private long frameIndex;
    private long startedAtNanos = System.nanoTime();
    private @Nullable StackPane boundContainer;
    private boolean boundToContainer;

    protected AbstractPackOverlay(DoubleSupplier animationSpeedSupplier) {
        this(animationSpeedSupplier, DEFAULT_FRAME_INTERVAL_MILLIS);
    }

    protected AbstractPackOverlay(DoubleSupplier animationSpeedSupplier, long frameIntervalMillis) {
        this.animationSpeedSupplier = Objects.requireNonNull(animationSpeedSupplier, "animationSpeedSupplier");
        setMouseTransparent(true);
        setManaged(false);
        timeline = new Timeline(new KeyFrame(Duration.millis(frameIntervalMillis), event -> tick()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        widthProperty().addListener((observable, oldValue, newValue) -> {
            onSizeChanged(getWidth(), getHeight());
            tick();
        });
        heightProperty().addListener((observable, oldValue, newValue) -> {
            onSizeChanged(getWidth(), getHeight());
            tick();
        });
    }

    final void start() {
        frameIndex = 0L;
        startedAtNanos = System.nanoTime();
        timeline.playFromStart();
        tick();
    }

    final void stop() {
        timeline.stop();
        onStopped();
    }

    /**
     * Sizes this overlay from the terminal container and enables background-tab handling.
     * Must be called on the JavaFX thread.
     */
    final void bindToContainer(StackPane container) {
        boundContainer = Objects.requireNonNull(container, "container");
        rebindToContainer();
    }

    /**
     * Unbinds, releases the canvas backing store and removes this overlay from its container.
     * Must be called on the JavaFX thread.
     */
    final void detachFromContainer() {
        StackPane container = boundContainer;
        boundContainer = null;
        releaseBackingStore();
        if (container != null) {
            container.getChildren().remove(this);
        }
    }

    private void rebindToContainer() {
        StackPane container = boundContainer;
        if (container == null) {
            return;
        }
        widthProperty().bind(container.widthProperty());
        heightProperty().bind(container.heightProperty());
        boundToContainer = true;
    }

    private void releaseBackingStore() {
        widthProperty().unbind();
        heightProperty().unbind();
        boundToContainer = false;
        setWidth(0.0);
        setHeight(0.0);
    }

    final void tick() {
        if (boundContainer != null) {
            if (!isShowingInScene()) {
                if (boundToContainer) {
                    releaseBackingStore();
                }
                return;
            }
            if (!boundToContainer) {
                rebindToContainer();
            }
        }
        double width = getWidth();
        double height = getHeight();
        if (width <= 0.0 || height <= 0.0) {
            return;
        }
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0.0, 0.0, width, height);
        frameIndex++;
        double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        double speed = TerminalEffectAnimationSpeed.normalize(animationSpeedSupplier.getAsDouble());
        paintFrame(gc, width, height, frameIndex, elapsedSeconds, speed);
    }

    private boolean isShowingInScene() {
        if (getScene() == null) {
            return false;
        }
        Node node = this;
        while (node != null) {
            if (!node.isVisible()) {
                return false;
            }
            node = node.getParent();
        }
        return true;
    }

    /**
     * Paints one overlay frame. {@code elapsedSeconds * speed} is the recommended "effect time"
     * for phase computations.
     */
    protected abstract void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed);

    /**
     * Called when the canvas is resized; regenerate cached size-dependent patterns here.
     */
    protected void onSizeChanged(double width, double height) {
    }

    /**
     * Called after the timeline stopped; clear transient animation state here.
     */
    protected void onStopped() {
    }

    protected final void drawScanlines(GraphicsContext gc, double width, double height, double spacing, double alpha) {
        gc.setFill(Color.rgb(0, 0, 0, alpha));
        for (double y = 0.0; y < height; y += spacing) {
            gc.fillRect(0.0, y, width, 1.0);
        }
    }

    protected final void drawVignette(GraphicsContext gc, double width, double height, double alpha) {
        drawVignette(gc, width, height, alpha, Color.BLACK);
    }

    protected final void drawVignette(GraphicsContext gc, double width, double height, double alpha, Color color) {
        gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        double edge = Math.max(24.0, Math.min(width, height) * 0.035);
        gc.fillRect(0.0, 0.0, edge, height);
        gc.fillRect(width - edge, 0.0, edge, height);
        gc.fillRect(0.0, 0.0, width, edge);
        gc.fillRect(0.0, height - edge, width, edge);
    }

    protected final void drawCornerBrackets(
            GraphicsContext gc, double width, double height, Color color, double length, double alpha) {
        gc.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        gc.setLineWidth(2.0);
        double inset = 10.0;
        // top-left
        gc.strokeLine(inset, inset, inset + length, inset);
        gc.strokeLine(inset, inset, inset, inset + length);
        // top-right
        gc.strokeLine(width - inset - length, inset, width - inset, inset);
        gc.strokeLine(width - inset, inset, width - inset, inset + length);
        // bottom-left
        gc.strokeLine(inset, height - inset, inset + length, height - inset);
        gc.strokeLine(inset, height - inset - length, inset, height - inset);
        // bottom-right
        gc.strokeLine(width - inset - length, height - inset, width - inset, height - inset);
        gc.strokeLine(width - inset, height - inset - length, width - inset, height - inset);
    }
}
