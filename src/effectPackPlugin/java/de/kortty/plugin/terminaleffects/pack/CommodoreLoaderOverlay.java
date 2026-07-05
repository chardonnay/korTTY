package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.function.DoubleSupplier;

/**
 * Classic C64 home computer: a thick light-blue border, soft scanlines and occasional
 * tape-loader raster bars flashing through the border zones.
 */
final class CommodoreLoaderOverlay extends AbstractPackOverlay {

    private static final Color BORDER_BLUE = Color.rgb(165, 159, 230);
    private static final Color[] LOADER_STRIPES = {
            Color.rgb(0, 0, 0),
            Color.rgb(255, 255, 255),
            Color.rgb(136, 57, 50),
            Color.rgb(103, 182, 189),
            Color.rgb(191, 206, 114)
    };
    private static final double BORDER_THICKNESS = 16.0;
    private static final double LOADER_BURST_SECONDS = 1.4;

    private double nextBurstAtEffectTime = 3.0;
    private double burstStartedAtEffectTime = Double.NEGATIVE_INFINITY;

    CommodoreLoaderOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;

        gc.setFill(Color.color(BORDER_BLUE.getRed(), BORDER_BLUE.getGreen(), BORDER_BLUE.getBlue(), 0.26));
        gc.fillRect(0.0, 0.0, width, BORDER_THICKNESS);
        gc.fillRect(0.0, height - BORDER_THICKNESS, width, BORDER_THICKNESS);
        gc.fillRect(0.0, 0.0, BORDER_THICKNESS, height);
        gc.fillRect(width - BORDER_THICKNESS, 0.0, BORDER_THICKNESS, height);

        drawScanlines(gc, width, height, 4.0, 0.10);

        if (effectTime >= nextBurstAtEffectTime) {
            burstStartedAtEffectTime = effectTime;
            nextBurstAtEffectTime = effectTime + LOADER_BURST_SECONDS + 6.0 + random.nextDouble() * 8.0;
        }
        if (effectTime - burstStartedAtEffectTime <= LOADER_BURST_SECONDS) {
            drawLoaderBars(gc, width, height, effectTime);
        }
    }

    private void drawLoaderBars(GraphicsContext gc, double width, double height, double effectTime) {
        double stripeHeight = 4.0;
        double scroll = effectTime * 320.0;
        for (double y = 0.0; y < BORDER_THICKNESS; y += stripeHeight) {
            Color stripe = LOADER_STRIPES[(int) (((y + scroll) / stripeHeight) % LOADER_STRIPES.length)];
            gc.setFill(Color.color(stripe.getRed(), stripe.getGreen(), stripe.getBlue(), 0.55));
            gc.fillRect(0.0, y, width, stripeHeight);
            gc.fillRect(0.0, height - BORDER_THICKNESS + y, width, stripeHeight);
        }
    }
}
