package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Haunted terminal: a slowly breathing vignette, sparse unsettling grain, random bursts of
 * dense static and a rare ghostly inverse flash that decays into a red afterglow.
 */
final class PoltergeistOverlay extends AbstractPackOverlay {

    private static final double VIGNETTE_PERIOD_SECONDS = 7.0;
    private static final Color GHOST_RED = Color.rgb(255, 43, 43);

    private double nextStaticAtEffectTime = 4.0;
    private double staticUntilEffectTime = Double.NEGATIVE_INFINITY;
    private double nextFlashAtEffectTime = 14.0;
    private double flashStartedAtEffectTime = Double.NEGATIVE_INFINITY;

    PoltergeistOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        // Constant unease: very sparse dark specks.
        gc.setFill(Color.color(0.0, 0.0, 0.0, 0.05));
        int darkPoints = (int) Math.max(20, (width * height) / 40000.0);
        for (int i = 0; i < darkPoints; i++) {
            gc.fillRect(random.nextDouble() * width, random.nextDouble() * height, 1.0, 1.0);
        }

        if (effectTime >= nextStaticAtEffectTime) {
            staticUntilEffectTime = effectTime + 0.3 + random.nextDouble() * 0.5;
            nextStaticAtEffectTime = staticUntilEffectTime + 5.0 + random.nextDouble() * 10.0;
        }
        if (effectTime <= staticUntilEffectTime) {
            gc.setFill(Color.color(0.78, 0.78, 0.78, 0.10));
            int points = (int) Math.max(120, (width * height) / 9000.0);
            for (int i = 0; i < points; i++) {
                double size = random.nextInt(5) == 0 ? 2.0 : 1.0;
                gc.fillRect(random.nextDouble() * width, random.nextDouble() * height, size, size);
            }
        }

        if (effectTime >= nextFlashAtEffectTime) {
            flashStartedAtEffectTime = effectTime;
            nextFlashAtEffectTime = effectTime + 20.0 + random.nextDouble() * 30.0;
        }
        double flashAge = effectTime - flashStartedAtEffectTime;
        if (flashAge >= 0.0 && flashAge < 0.08) {
            gc.setFill(Color.color(1.0, 1.0, 1.0, 0.07));
            gc.fillRect(0.0, 0.0, width, height);
        } else if (flashAge >= 0.08 && flashAge < 0.6) {
            double decay = 1.0 - (flashAge - 0.08) / 0.52;
            gc.setFill(Color.color(GHOST_RED.getRed(), GHOST_RED.getGreen(), GHOST_RED.getBlue(), 0.05 * decay));
            gc.fillRect(0.0, 0.0, width, height);
        }

        double breath = 0.5 + 0.5 * Math.sin(effectTime * 2.0 * Math.PI / VIGNETTE_PERIOD_SECONDS);
        drawBreathingVignette(gc, width, height, 0.12 + 0.14 * breath);
    }

    private void drawBreathingVignette(GraphicsContext gc, double width, double height, double alpha) {
        gc.setFill(Color.color(0.0, 0.0, 0.0, alpha));
        double edge = Math.max(30.0, Math.min(width, height) * 0.06);
        gc.fillRect(0.0, 0.0, edge, height);
        gc.fillRect(width - edge, 0.0, edge, height);
        gc.fillRect(0.0, 0.0, width, edge);
        gc.fillRect(0.0, height - edge, width, edge);
        // Softer second ring for a deeper corner falloff.
        gc.setFill(Color.color(0.0, 0.0, 0.0, alpha * 0.5));
        double outer = edge * 2.0;
        gc.fillRect(0.0, 0.0, outer, height);
        gc.fillRect(width - outer, 0.0, outer, height);
    }
}
