package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Tactical deep-space console: a slow rotating radar sweep, faint range rings, short-lived
 * contact blips near the sweep and thin frame corners.
 */
final class DeepSpaceRadarOverlay extends AbstractPackOverlay {

    private static final Color RADAR_GREEN = Color.rgb(53, 208, 138);
    private static final double SWEEP_ROTATION_SECONDS = 8.0;
    private static final double SWEEP_EXTENT_DEGREES = 32.0;
    private static final double BLIP_LIFETIME_SECONDS = 2.5;
    private static final long FRAME_INTERVAL_MILLIS = 50L;

    private final List<Blip> blips = new ArrayList<>();
    private double nextBlipAtEffectTime = 1.5;

    DeepSpaceRadarOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed, FRAME_INTERVAL_MILLIS);
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        double effectTime = elapsedSeconds * speed;
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double maxRadius = Math.hypot(width, height) / 2.0;

        double sweepDegrees = (effectTime / SWEEP_ROTATION_SECONDS * 360.0) % 360.0;
        gc.setFill(Color.color(RADAR_GREEN.getRed(), RADAR_GREEN.getGreen(), RADAR_GREEN.getBlue(), 0.045));
        gc.fillArc(centerX - maxRadius, centerY - maxRadius, maxRadius * 2.0, maxRadius * 2.0,
                sweepDegrees, SWEEP_EXTENT_DEGREES, ArcType.ROUND);
        double leadRadians = Math.toRadians(sweepDegrees + SWEEP_EXTENT_DEGREES);
        gc.setStroke(Color.color(RADAR_GREEN.getRed(), RADAR_GREEN.getGreen(), RADAR_GREEN.getBlue(), 0.10));
        gc.setLineWidth(1.5);
        gc.strokeLine(centerX, centerY,
                centerX + Math.cos(leadRadians) * maxRadius,
                centerY - Math.sin(leadRadians) * maxRadius);

        gc.setStroke(Color.color(RADAR_GREEN.getRed(), RADAR_GREEN.getGreen(), RADAR_GREEN.getBlue(), 0.05));
        gc.setLineWidth(1.0);
        double ringBase = Math.min(width, height);
        for (double factor : new double[]{0.28, 0.5, 0.72}) {
            double radius = ringBase * factor / 1.2;
            gc.strokeOval(centerX - radius, centerY - radius, radius * 2.0, radius * 2.0);
        }

        if (effectTime >= nextBlipAtEffectTime) {
            blips.add(new Blip(
                    centerX + (random.nextDouble() - 0.5) * width * 0.8,
                    centerY + (random.nextDouble() - 0.5) * height * 0.8,
                    effectTime));
            nextBlipAtEffectTime = effectTime + 2.0 + random.nextDouble() * 4.0;
        }
        drawBlips(gc, effectTime);

        drawCornerBrackets(gc, width, height, RADAR_GREEN, 22.0, 0.22);
    }

    private void drawBlips(GraphicsContext gc, double effectTime) {
        Iterator<Blip> iterator = blips.iterator();
        while (iterator.hasNext()) {
            Blip blip = iterator.next();
            double age = effectTime - blip.bornAtEffectTime();
            if (age >= BLIP_LIFETIME_SECONDS || age < 0.0) {
                iterator.remove();
                continue;
            }
            double fade = 1.0 - age / BLIP_LIFETIME_SECONDS;
            gc.setFill(Color.color(RADAR_GREEN.getRed(), RADAR_GREEN.getGreen(), RADAR_GREEN.getBlue(), 0.16 * fade));
            gc.fillOval(blip.x() - 2.0, blip.y() - 2.0, 4.0, 4.0);
            double ringRadius = 4.0 + age * 10.0;
            gc.setStroke(Color.color(RADAR_GREEN.getRed(), RADAR_GREEN.getGreen(), RADAR_GREEN.getBlue(), 0.10 * fade));
            gc.setLineWidth(1.0);
            gc.strokeOval(blip.x() - ringRadius, blip.y() - ringRadius, ringRadius * 2.0, ringRadius * 2.0);
        }
    }

    @Override
    protected void onStopped() {
        blips.clear();
    }

    private record Blip(double x, double y, double bornAtEffectTime) {
    }
}
