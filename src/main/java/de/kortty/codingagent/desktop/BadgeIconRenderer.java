package de.kortty.codingagent.desktop;

import javafx.geometry.VPos;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Renders the application icon with a red counter bubble in the lower-right corner (Windows
 * taskbar). Must run on the JavaFX thread; the drawing path is exercised by the xvfb smoke, not by
 * unit tests.
 */
public final class BadgeIconRenderer {

    /** Reference size the geometry below is specified for; other sizes scale proportionally. */
    private static final double REFERENCE_SIZE = 256.0;
    private static final double BUBBLE_CENTRE = 198.0;
    private static final double BUBBLE_RADIUS = 52.0;
    private static final double BUBBLE_STROKE = 6.0;
    private static final double BADGE_FONT_SIZE = 60.0;
    private static final String BUBBLE_FILL = "#d93025";

    private BadgeIconRenderer() {
    }

    /**
     * Draws {@code base} scaled to {@code size}×{@code size} and, for a positive {@code count}, the
     * counter bubble with {@link #badgeText(int)}.
     *
     * @param base the plain application icon (may be {@code null}: the bubble is drawn alone)
     * @param count the blocked-agent count; {@code 0} or less yields the plain icon
     * @param size the edge length of the rendered image in pixels
     * @return a snapshot with a transparent background
     */
    public static Image render(Image base, int count, int size) {
        int edge = Math.max(1, size);
        double scale = edge / REFERENCE_SIZE;
        Canvas canvas = new Canvas(edge, edge);
        GraphicsContext g = canvas.getGraphicsContext2D();
        if (base != null) {
            g.drawImage(base, 0, 0, edge, edge);
        }
        if (count > 0) {
            double centre = BUBBLE_CENTRE * scale;
            double radius = BUBBLE_RADIUS * scale;
            g.setFill(Color.web(BUBBLE_FILL));
            g.fillOval(centre - radius, centre - radius, radius * 2, radius * 2);
            g.setStroke(Color.WHITE);
            g.setLineWidth(BUBBLE_STROKE * scale);
            g.strokeOval(centre - radius, centre - radius, radius * 2, radius * 2);
            g.setFill(Color.WHITE);
            g.setFont(Font.font("System", FontWeight.BOLD, BADGE_FONT_SIZE * scale));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(badgeText(count), centre, centre);
        }
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }

    /** The bubble text: the count up to nine, {@code "9+"} beyond, empty for zero or less. */
    public static String badgeText(int count) {
        if (count <= 0) {
            return "";
        }
        return count <= 9 ? Integer.toString(count) : "9+";
    }
}
