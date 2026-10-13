package de.kortty.ui;

import de.kortty.model.TerminalLogBadge;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;

/** The same two-layer SVG glyphs as the menu toolbar, with five badge treatments. */
final class LogfileBadgeView {
    private LogfileBadgeView() { }
    static Label create(TerminalLogBadge badge) {
        Label label = new Label(badge.name());
        label.setGraphic(ToolbarIcons.create(badge.icon(), label.textFillProperty(), new SimpleDoubleProperty(16)));
        label.setGraphicTextGap(5);
        Color color = Color.web(badge.color());
        String rgb = (int) (color.getRed() * 255) + "," + (int) (color.getGreen() * 255) + "," + (int) (color.getBlue() * 255);
        String base = "-fx-padding: 3 7; -fx-background-radius: 10; -fx-border-radius: 10;";
        String style = switch (badge.style()) {
            case SOFT -> "-fx-background-color: rgba(" + rgb + ",0.20);";
            case OUTLINE -> "-fx-border-color: " + badge.color() + ";";
            case SOLID -> "-fx-background-color: " + badge.color() + "; -fx-text-fill: "
                + (luminance(color) > 0.179 ? "#111111" : "#ffffff") + ";";
            case FLAG -> "-fx-border-color: transparent transparent transparent " + badge.color()
                + "; -fx-border-width: 0 0 0 3; -fx-border-radius: 0;";
            case MINIMAL -> "-fx-padding: 3 2;";
        };
        label.setStyle(base + style);
        label.setTooltip(new javafx.scene.control.Tooltip(badge.hold() ? I18n.get("logfile.badges.holdHint") : badge.name()));
        return label;
    }
    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }
    private static double linear(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
}
