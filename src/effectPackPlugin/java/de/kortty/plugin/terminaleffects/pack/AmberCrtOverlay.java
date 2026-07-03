package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Amber phosphor CRT monitor from the 90s: scanlines, warm phosphor glow rows, brightness
 * flicker, a slowly rolling refresh band and a dark vignette.
 */
final class AmberCrtOverlay extends AbstractPackOverlay {

    private static final Color AMBER = Color.rgb(255, 176, 0);
    private static final Color AMBER_BRIGHT = Color.rgb(255, 214, 96);
    private static final double REFRESH_BAND_PERIOD_SECONDS = 6.0;
    private static final double REFRESH_BAND_HEIGHT = 64.0;

    AmberCrtOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        // Brightness flicker: slow breathing plus a tiny per-frame jitter, like a tired tube.
        double flicker = 0.02
                + 0.02 * (0.5 + 0.5 * Math.sin(effectTime * 2.1))
                + random.nextDouble() * 0.008;
        gc.setFill(Color.color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), flicker));
        gc.fillRect(0.0, 0.0, width, height);

        drawScanlines(gc, width, height, 3.0, 0.22);

        gc.setFill(Color.color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 0.04));
        for (double y = 1.0; y < height; y += 7.0) {
            gc.fillRect(0.0, y, width, 1.0);
        }

        drawRefreshBand(gc, width, height, effectTime);
        drawVignette(gc, width, height, 0.18);
    }

    private void drawRefreshBand(GraphicsContext gc, double width, double height, double effectTime) {
        double progress = (effectTime % REFRESH_BAND_PERIOD_SECONDS) / REFRESH_BAND_PERIOD_SECONDS;
        double bandTop = progress * (height + REFRESH_BAND_HEIGHT) - REFRESH_BAND_HEIGHT;
        int slices = 8;
        double sliceHeight = REFRESH_BAND_HEIGHT / slices;
        for (int i = 0; i < slices; i++) {
            // Brightest at the band's leading (lower) edge, fading toward the top.
            double intensity = (i + 1) / (double) slices;
            double alpha = 0.055 * intensity;
            gc.setFill(Color.color(
                    AMBER_BRIGHT.getRed(), AMBER_BRIGHT.getGreen(), AMBER_BRIGHT.getBlue(), alpha));
            gc.fillRect(0.0, bandTop + i * sliceHeight, width, sliceHeight + 1.0);
        }
    }
}
