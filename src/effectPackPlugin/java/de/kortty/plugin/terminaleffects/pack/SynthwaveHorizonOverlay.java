package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * 80s synthwave: a pulsing magenta/violet glow and a glowing perspective grid along the bottom
 * edge whose horizontal lines scroll toward the viewer.
 */
final class SynthwaveHorizonOverlay extends AbstractPackOverlay {

    private static final Color GRID_PINK = Color.rgb(255, 113, 206);
    private static final Color GLOW_VIOLET = Color.rgb(138, 43, 226);
    private static final double GRID_SCROLL_SECONDS = 2.5;
    private static final int VERTICAL_LINES = 13;
    private static final int HORIZONTAL_LINES = 7;

    SynthwaveHorizonOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        double breath = 0.5 + 0.5 * Math.sin(effectTime * 2.0 * Math.PI / 4.0);
        gc.setFill(Color.color(GRID_PINK.getRed(), GRID_PINK.getGreen(), GRID_PINK.getBlue(), 0.02 + 0.02 * breath));
        gc.fillRect(0.0, 0.0, width, height);
        gc.setFill(Color.color(GLOW_VIOLET.getRed(), GLOW_VIOLET.getGreen(), GLOW_VIOLET.getBlue(), 0.025));
        gc.fillRect(0.0, height * 0.5, width, height * 0.5);

        drawScanlines(gc, width, height, 5.0, 0.08);
        drawGrid(gc, width, height, effectTime);
    }

    private void drawGrid(GraphicsContext gc, double width, double height, double effectTime) {
        double gridHeight = Math.max(40.0, height * 0.16);
        double horizonY = height - gridHeight;
        double vanishingX = width / 2.0;

        gc.setStroke(Color.color(GRID_PINK.getRed(), GRID_PINK.getGreen(), GRID_PINK.getBlue(), 0.25));
        gc.setLineWidth(1.0);
        gc.strokeLine(0.0, horizonY, width, horizonY);

        gc.setStroke(Color.color(GRID_PINK.getRed(), GRID_PINK.getGreen(), GRID_PINK.getBlue(), 0.15));
        for (int i = 0; i < VERTICAL_LINES; i++) {
            double bottomX = vanishingX + (i - VERTICAL_LINES / 2) * (width / 6.0);
            gc.strokeLine(vanishingX, horizonY, bottomX, height);
        }

        for (int k = 0; k < HORIZONTAL_LINES; k++) {
            double progress = (effectTime / GRID_SCROLL_SECONDS + k / (double) HORIZONTAL_LINES) % 1.0;
            // Quadratic progression fakes perspective: lines accelerate as they approach the viewer.
            double y = horizonY + progress * progress * gridHeight;
            double alpha = 0.06 + 0.14 * progress;
            gc.setStroke(Color.color(GRID_PINK.getRed(), GRID_PINK.getGreen(), GRID_PINK.getBlue(), alpha));
            gc.strokeLine(0.0, y, width, y);
        }
    }
}
