package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.DoubleSupplier;

/**
 * Worn VHS tape playback: a jittering tracking-noise band at the bottom, an occasional rolling
 * distortion bar, thin chroma-bleed lines and a flickering "PLAY" OSD in the top-right corner.
 */
final class VhsOverlay extends AbstractPackOverlay {

    private static final Color BLEED_MAGENTA = Color.rgb(255, 80, 200);
    private static final Color BLEED_CYAN = Color.rgb(80, 220, 255);
    private static final double TRACKING_BAND_HEIGHT = 22.0;
    private static final double DISTORTION_ROLL_SECONDS = 1.5;

    private double nextRollAtEffectTime = 5.0;
    private double rollStartedAtEffectTime = Double.NEGATIVE_INFINITY;
    private Font osdFont;

    VhsOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        gc.setFill(Color.color(1.0, 1.0, 1.0, 0.04));
        int noisePoints = (int) Math.max(40, (width * height) / 30000.0);
        for (int i = 0; i < noisePoints; i++) {
            gc.fillRect(random.nextDouble() * width, random.nextDouble() * height, 1.0, 1.0);
        }

        drawTrackingBand(gc, width, height);
        drawChromaBleed(gc, width, height);

        if (effectTime >= nextRollAtEffectTime) {
            rollStartedAtEffectTime = effectTime;
            nextRollAtEffectTime = effectTime + 8.0 + random.nextDouble() * 12.0;
        }
        double rollAge = effectTime - rollStartedAtEffectTime;
        if (rollAge >= 0.0 && rollAge <= DISTORTION_ROLL_SECONDS) {
            double y = height - (rollAge / DISTORTION_ROLL_SECONDS) * (height + 40.0);
            gc.setFill(Color.color(1.0, 1.0, 1.0, 0.06));
            gc.fillRect(0.0, y, width, 26.0);
            gc.setFill(Color.color(BLEED_MAGENTA.getRed(), BLEED_MAGENTA.getGreen(), BLEED_MAGENTA.getBlue(), 0.06));
            gc.fillRect(0.0, y - 2.0, width, 2.0);
            gc.setFill(Color.color(BLEED_CYAN.getRed(), BLEED_CYAN.getGreen(), BLEED_CYAN.getBlue(), 0.06));
            gc.fillRect(0.0, y + 26.0, width, 2.0);
        }

        drawPlayOsd(gc, width, effectTime);
        drawVignette(gc, width, height, 0.10);
    }

    private void drawTrackingBand(GraphicsContext gc, double width, double height) {
        double bandTop = height - TRACKING_BAND_HEIGHT;
        gc.setFill(Color.color(0.6, 0.6, 0.6, 0.05));
        gc.fillRect(0.0, bandTop, width, TRACKING_BAND_HEIGHT);
        int strips = 8 + random.nextInt(7);
        for (int i = 0; i < strips; i++) {
            double y = bandTop + random.nextDouble() * (TRACKING_BAND_HEIGHT - 2.0);
            double stripWidth = 20.0 + random.nextDouble() * 60.0;
            double x = random.nextDouble() * width - stripWidth / 2.0;
            gc.setFill(Color.color(1.0, 1.0, 1.0, 0.10 + random.nextDouble() * 0.10));
            gc.fillRect(x, y, stripWidth, 2.0);
        }
    }

    private void drawChromaBleed(GraphicsContext gc, double width, double height) {
        for (int i = 0; i < 3; i++) {
            double y = random.nextDouble() * height;
            double x = random.nextDouble() * width * 0.5;
            double lineWidth = width * (0.2 + random.nextDouble() * 0.3);
            gc.setFill(Color.color(BLEED_MAGENTA.getRed(), BLEED_MAGENTA.getGreen(), BLEED_MAGENTA.getBlue(), 0.04));
            gc.fillRect(x, y, lineWidth, 1.0);
            gc.setFill(Color.color(BLEED_CYAN.getRed(), BLEED_CYAN.getGreen(), BLEED_CYAN.getBlue(), 0.04));
            gc.fillRect(x + 2.0, y + 1.0, lineWidth, 1.0);
        }
    }

    private void drawPlayOsd(GraphicsContext gc, double width, double effectTime) {
        if (osdFont == null) {
            osdFont = Font.font("Monospaced", FontWeight.BOLD, 13.0);
        }
        double alpha = 0.42 + 0.08 * Math.sin(effectTime * 9.0) + random.nextDouble() * 0.04;
        gc.setFill(Color.color(1.0, 1.0, 1.0, Math.max(0.25, Math.min(0.55, alpha))));
        double baseX = width - 74.0;
        double baseY = 26.0;
        // Triangle drawn explicitly; not all monospace fonts cover U+25B6.
        gc.fillPolygon(
                new double[]{baseX, baseX, baseX + 9.0},
                new double[]{baseY - 10.0, baseY, baseY - 5.0},
                3);
        gc.setFont(osdFont);
        gc.fillText("PLAY", baseX + 15.0, baseY - 1.0);
    }
}
