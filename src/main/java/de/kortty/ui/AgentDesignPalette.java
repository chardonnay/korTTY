package de.kortty.ui;

import de.kortty.model.Theme;
import javafx.scene.paint.Color;

/**
 * The colours the KI-Agent design bars and lists draw with, from the agent panel colours of the
 * terminal's theme. Status colours are fixed per brightness so they read on any theme.
 */
record AgentDesignPalette(
    boolean dark,
    Color background,
    Color surface,
    Color border,
    Color text,
    Color muted,
    Color accent,
    Color ok,
    Color warn,
    Color error,
    Color think) {

    static AgentDesignPalette of(Theme theme) {
        return of(ThemeCssSupport.resolveAgentActivityColors(theme));
    }

    static AgentDesignPalette of(ThemeCssSupport.AgentActivityColors colors) {
        Color background = Color.web(colors.backgroundColor());
        boolean dark = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue() < 0.5;
        Color accent = Color.web(colors.accentColor());
        return new AgentDesignPalette(
            dark,
            background,
            background.interpolate(dark ? Color.WHITE : Color.BLACK, dark ? 0.06 : 0.03),
            Color.web(colors.borderColor()),
            Color.web(colors.textColor()),
            Color.web(colors.mutedTextColor()),
            accent,
            dark ? Color.web("#3FB68B") : Color.web("#1F8A5F"),
            dark ? Color.web("#E5A84B") : Color.web("#B7791F"),
            Color.web(colors.errorColor()),
            dark ? Color.web("#A48CFF") : Color.web("#6B46C1"));
    }

    /** The colour of a piece of step text by its role in {@link AgentInlineBlockLayout}. */
    Color colorOf(AgentInlineBlockLayout.Role role) {
        return switch (role) {
            case BORDER -> border.interpolate(accent, 0.4);
            case ACCENT, KEY -> accent;
            case TITLE, TEXT -> text;
            case MUTED -> muted;
            case OK -> ok;
            case WARN -> warn;
            case ERROR -> error;
            case THINK -> think;
        };
    }

    /** {@code #RRGGBB} of a colour, for inline styles. */
    static String hex(Color color) {
        return String.format("#%02X%02X%02X",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
    }

    /** {@code rgba(r,g,b,a)} of a colour with the given opacity, for inline styles. */
    static String rgba(Color color, double opacity) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255),
            opacity);
    }
}
