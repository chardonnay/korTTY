package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.model.GlobalSettings;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Locale;

/**
 * How a connection's tab color looks on a terminal tab: a small dot in the tab header (the tab's
 * graphic), named in the tab's tooltip and in the dot's accessible text together with where it comes
 * from (the connection, its group or its credential environment) so the color is never the only cue, and optionally a frame of that color around the terminal. The tab's style stays with
 * the connection-status colors (connecting, failed), which a connection color must never overwrite.
 */
final class TabColorPresentation {

    /** Radius of the color dot in the tab header, so the dot is 10 px wide. */
    static final double SWATCH_RADIUS = 5.0;

    /** Width of the frame around a colored connection's terminal, in pixels on each side. */
    static final double FRAME_WIDTH = 3.0;

    /** Style class of the color dot, for app designs that want to restyle it. */
    static final String SWATCH_STYLE_CLASS = "tab-connection-color";

    /** The longest environment name a tab's tooltip shows, in characters. */
    static final int MAX_ENVIRONMENT_NAME_LENGTH = 60;

    /** The longest group path a tab's tooltip shows, in characters. */
    static final int MAX_GROUP_NAME_LENGTH = 80;

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

    /**
     * The frame around the terminal of a connection colored {@code hex} ({@code #RRGGBB}): one solid
     * {@link #FRAME_WIDTH} px stroke on every side, square-cornered. It is the border of the tab's
     * content box, which holds the split panes and the status bars, so it lies outside every pane and
     * its focus marking, and it leaves the terminal view's style, which owns the see-through
     * background, alone. A region's border takes layout space: the terminal is 3 px smaller on each
     * side while the frame shows.
     */
    static Border frame(String hex) {
        return new Border(new BorderStroke(Color.web(hex), BorderStrokeStyle.SOLID, CornerRadii.EMPTY,
                new BorderWidths(FRAME_WIDTH)));
    }

    /**
     * The border of a terminal tab's content: the {@link #frame} of {@code hex} while the frame is
     * switched on in the Window settings, otherwise none. A tab without a color ({@code null}) never
     * gets one.
     */
    static Border frameFor(String hex, boolean frameEnabled) {
        return hex != null && frameEnabled ? frame(hex) : null;
    }

    /** Whether a colored connection's terminal gets the frame: on unless switched off in the Window settings. */
    static boolean frameEnabled(GlobalSettings settings) {
        return settings == null || settings.isConnectionColorBorderEnabled();
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
     * The name of the credential environment a tab's color comes from, as its tooltip shows it:
     * the display name with control and bidi characters removed and capped at
     * {@link #MAX_ENVIRONMENT_NAME_LENGTH} characters, or the environment's id when nothing visible
     * is left of the name.
     */
    static String environmentLabel(String displayName, String environmentId) {
        String name = DisplayTextSanitizer.sanitize(displayName, MAX_ENVIRONMENT_NAME_LENGTH);
        return name.isEmpty() ? DisplayTextSanitizer.sanitize(environmentId, MAX_ENVIRONMENT_NAME_LENGTH) : name;
    }

    /**
     * The group a tab's color comes from, as its tooltip shows it: the group's path
     * ({@code Work/Production}) with control and bidi characters removed and capped at
     * {@link #MAX_GROUP_NAME_LENGTH} characters.
     */
    static String groupLabel(String groupPath) {
        return DisplayTextSanitizer.sanitize(groupPath, MAX_GROUP_NAME_LENGTH);
    }

    /**
     * The i18n key of the tooltip line that names a tab's color and where it comes from: set on the
     * connection ({@code null} counts as that), from its group or from its credential environment.
     */
    static String colorLineKey(ConnectionColorSupport.Source source) {
        if (source == null) {
            return "tab.tooltip.connectionColor";
        }
        return switch (source) {
            case CONNECTION -> "tab.tooltip.connectionColor";
            case GROUP -> "tab.tooltip.groupColor";
            case ENVIRONMENT -> "tab.tooltip.environmentColor";
        };
    }

    /**
     * The tooltip line that names a tab's color by {@code familyName} and {@code hex} and says where
     * it comes from; {@code sourceName} is the group or environment ({@link #groupLabel},
     * {@link #environmentLabel}) and is not used for a color set on the connection.
     */
    static String colorLine(ConnectionColorSupport.Source source, String sourceName, String familyName, String hex) {
        String key = colorLineKey(source);
        return key.equals("tab.tooltip.connectionColor")
                ? I18n.get(key, familyName, hex)
                : I18n.get(key, familyName, hex, sourceName != null ? sourceName : "");
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
