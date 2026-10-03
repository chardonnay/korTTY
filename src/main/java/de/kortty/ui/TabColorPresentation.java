package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import javafx.scene.AccessibleRole;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Locale;

/**
 * How a connection's tab color looks on a terminal tab: a small dot in the tab header (the tab's
 * graphic), named in the tab's tooltip and in the dot's accessible text so the color is never the
 * only cue. The tab's style stays with the connection-status colors (connecting, failed), which a
 * connection color must never overwrite.
 */
final class TabColorPresentation {

    /** Radius of the color dot in the tab header, so the dot is 10 px wide. */
    static final double SWATCH_RADIUS = 5.0;

    /** Style class of the color dot, for app designs that want to restyle it. */
    static final String SWATCH_STYLE_CLASS = "tab-connection-color";

    private TabColorPresentation() {
    }

    /**
     * The color dot for {@code hex} ({@code #RRGGBB}), outlined so it stays visible on light and
     * dark tab headers and on the yellow and red status colors. Mouse-transparent, so clicks and
     * drags reach the tab header; screen readers read {@code accessibleText}.
     */
    static Circle swatch(String hex, String accessibleText) {
        Color fill = Color.web(hex);
        Circle dot = new Circle(SWATCH_RADIUS, fill);
        dot.setStroke(strokeFor(fill));
        dot.setStrokeWidth(1.0);
        dot.setMouseTransparent(true);
        dot.getStyleClass().add(SWATCH_STYLE_CLASS);
        dot.setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        dot.setAccessibleText(accessibleText);
        return dot;
    }

    /** A light outline around dark colors and a dark one around light colors. */
    static Color strokeFor(Color fill) {
        double luminance = 0.2126 * fill.getRed() + 0.7152 * fill.getGreen() + 0.0722 * fill.getBlue();
        return luminance < 0.35 ? Color.rgb(255, 255, 255, 0.75) : Color.rgb(0, 0, 0, 0.5);
    }

    /** {@code color} as an upper-case {@code #RRGGBB}, rounded rather than truncated. */
    static String hexOf(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    /** The i18n key naming {@code family} in the tab's tooltip, for example {@code tab.tooltip.color.red}. */
    static String familyKey(ConnectionColorSupport.Family family) {
        return "tab.tooltip.color." + family.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The text that explains a colored tab: the connection line, then the color line, joined by
     * {@code separator} (a line break for the tooltip, a comma for screen readers). A blank
     * connection line is left out.
     */
    static String describe(String colorLine, String connectionLine, String separator) {
        if (connectionLine == null || connectionLine.isBlank()) {
            return colorLine;
        }
        return connectionLine + separator + colorLine;
    }
}
