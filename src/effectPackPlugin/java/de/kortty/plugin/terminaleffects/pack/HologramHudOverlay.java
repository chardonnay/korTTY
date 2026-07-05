package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Sci-fi hologram HUD: translucent cyan tint, slowly drifting interference bands, breathing
 * corner brackets and an occasional projector flicker.
 */
final class HologramHudOverlay extends AbstractPackOverlay {

    private static final Color HOLO_CYAN = Color.rgb(127, 231, 255);
    private static final int BAND_COUNT = 3;

    private double nextFlickerAtEffectTime = 3.0;
    private long flickerFramesRemaining;

    HologramHudOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        gc.setFill(Color.color(HOLO_CYAN.getRed(), HOLO_CYAN.getGreen(), HOLO_CYAN.getBlue(), 0.035));
        gc.fillRect(0.0, 0.0, width, height);

        for (int band = 0; band < BAND_COUNT; band++) {
            double bandSpeed = 14.0 + band * 9.0;
            double bandHeight = 26.0 + band * 16.0;
            double travel = height + bandHeight;
            double y = ((effectTime * bandSpeed + band * travel / BAND_COUNT) % travel) - bandHeight;
            gc.setFill(Color.color(HOLO_CYAN.getRed(), HOLO_CYAN.getGreen(), HOLO_CYAN.getBlue(), 0.05));
            gc.fillRect(0.0, y, width, bandHeight);
            gc.setFill(Color.color(1.0, 1.0, 1.0, 0.02));
            gc.fillRect(0.0, y + bandHeight * 0.4, width, 1.0);
        }

        drawScanlines(gc, width, height, 5.0, 0.08);

        double bracketBreath = 0.30 + 0.10 * (0.5 + 0.5 * Math.sin(effectTime * 1.5));
        drawCornerBrackets(gc, width, height, HOLO_CYAN, 26.0, bracketBreath);

        if (effectTime >= nextFlickerAtEffectTime && flickerFramesRemaining <= 0) {
            flickerFramesRemaining = 1 + random.nextInt(2);
            nextFlickerAtEffectTime = effectTime + 4.0 + random.nextDouble() * 6.0;
        }
        if (flickerFramesRemaining > 0) {
            flickerFramesRemaining--;
            gc.setFill(Color.color(HOLO_CYAN.getRed(), HOLO_CYAN.getGreen(), HOLO_CYAN.getBlue(), 0.08));
            gc.fillRect(0.0, 0.0, width, height);
        }
    }

    @Override
    protected void onStopped() {
        flickerFramesRemaining = 0;
    }
}
