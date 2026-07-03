package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Sepia paper noir: static paper grain (regenerated only on resize), a warm breathing vignette
 * and nothing that moves fast — the typewriter rhythm comes from the paced connector.
 */
final class TypewriterNoirOverlay extends AbstractPackOverlay {

    private static final Color SEPIA_SHADOW = Color.rgb(59, 47, 30);
    private static final Color GRAIN_DARK = Color.rgb(107, 91, 69);
    private static final Color GRAIN_LIGHT = Color.rgb(255, 248, 232);
    private static final double VIGNETTE_PERIOD_SECONDS = 9.0;
    private static final long FRAME_INTERVAL_MILLIS = 100L;

    private double[] grainXs = new double[0];
    private double[] grainYs = new double[0];
    private boolean[] grainDark = new boolean[0];

    TypewriterNoirOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed, FRAME_INTERVAL_MILLIS);
    }

    @Override
    protected void onSizeChanged(double width, double height) {
        int count = (int) Math.max(60, (width * height) / 2500.0);
        grainXs = new double[count];
        grainYs = new double[count];
        grainDark = new boolean[count];
        for (int i = 0; i < count; i++) {
            grainXs[i] = random.nextDouble() * Math.max(1.0, width);
            grainYs[i] = random.nextDouble() * Math.max(1.0, height);
            grainDark[i] = random.nextBoolean();
        }
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        if (grainXs.length == 0) {
            onSizeChanged(width, height);
        }
        double effectTime = elapsedSeconds * speed;

        for (int i = 0; i < grainXs.length; i++) {
            Color grain = grainDark[i] ? GRAIN_DARK : GRAIN_LIGHT;
            gc.setFill(Color.color(grain.getRed(), grain.getGreen(), grain.getBlue(), 0.05));
            gc.fillRect(grainXs[i], grainYs[i], 1.0, 1.0);
        }

        double breath = 0.5 + 0.5 * Math.sin(effectTime * 2.0 * Math.PI / VIGNETTE_PERIOD_SECONDS);
        double alpha = 0.06 + 0.03 * breath;
        gc.setFill(Color.color(SEPIA_SHADOW.getRed(), SEPIA_SHADOW.getGreen(), SEPIA_SHADOW.getBlue(), alpha));
        double edge = Math.max(28.0, Math.min(width, height) * 0.06);
        gc.fillRect(0.0, 0.0, edge, height);
        gc.fillRect(width - edge, 0.0, edge, height);
        gc.fillRect(0.0, 0.0, width, edge);
        // The bottom edge is a touch heavier, like a page curling out of the platen.
        gc.fillRect(0.0, height - edge, width, edge);
        gc.setFill(Color.color(SEPIA_SHADOW.getRed(), SEPIA_SHADOW.getGreen(), SEPIA_SHADOW.getBlue(), alpha * 0.6));
        gc.fillRect(0.0, height - edge * 1.8, width, edge * 0.8);
    }
}
