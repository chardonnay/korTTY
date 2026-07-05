package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Cyberpunk neon: a breathing magenta-to-cyan wash, glitch tears with RGB-split ghost strips
 * and rare thin white flash lines.
 */
final class NeonCityOverlay extends AbstractPackOverlay {

    private static final Color MAGENTA = Color.rgb(255, 46, 136);
    private static final Color CYAN = Color.rgb(0, 229, 255);

    private double nextGlitchAtEffectTime = 2.0;
    private long glitchFramesRemaining;
    private final double[] glitchYs = new double[4];
    private final double[] glitchHeights = new double[4];
    private int glitchStripCount;

    NeonCityOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        // Breathing dual-tone wash: magenta from the top, cyan from the bottom.
        double breath = 0.5 + 0.5 * Math.sin(effectTime * 2.0 * Math.PI / 3.0);
        gc.setFill(Color.color(MAGENTA.getRed(), MAGENTA.getGreen(), MAGENTA.getBlue(), 0.025 + 0.02 * breath));
        gc.fillRect(0.0, 0.0, width, height * 0.55);
        gc.setFill(Color.color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 0.025 + 0.02 * (1.0 - breath)));
        gc.fillRect(0.0, height * 0.45, width, height * 0.55);

        drawScanlines(gc, width, height, 4.0, 0.10);

        if (effectTime >= nextGlitchAtEffectTime && glitchFramesRemaining <= 0) {
            glitchStripCount = 2 + random.nextInt(3);
            for (int i = 0; i < glitchStripCount; i++) {
                glitchYs[i] = random.nextDouble() * height;
                glitchHeights[i] = 5.0 + random.nextDouble() * 10.0;
            }
            glitchFramesRemaining = 2 + random.nextInt(4);
            nextGlitchAtEffectTime = effectTime + 3.0 + random.nextDouble() * 6.0;
        }
        if (glitchFramesRemaining > 0) {
            glitchFramesRemaining--;
            for (int i = 0; i < glitchStripCount; i++) {
                double y = glitchYs[i];
                double stripHeight = glitchHeights[i];
                gc.setFill(Color.color(1.0, 1.0, 1.0, 0.05));
                gc.fillRect(0.0, y, width, stripHeight);
                gc.setFill(Color.color(MAGENTA.getRed(), MAGENTA.getGreen(), MAGENTA.getBlue(), 0.11));
                gc.fillRect(-6.0, y, width, stripHeight);
                gc.setFill(Color.color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 0.11));
                gc.fillRect(6.0, y, width, stripHeight);
            }
            if (random.nextInt(3) == 0) {
                gc.setFill(Color.color(1.0, 1.0, 1.0, 0.12));
                gc.fillRect(0.0, random.nextDouble() * height, width, 1.0);
            }
        }

        drawVignette(gc, width, height, 0.10);
    }

    @Override
    protected void onStopped() {
        glitchFramesRemaining = 0;
    }
}
