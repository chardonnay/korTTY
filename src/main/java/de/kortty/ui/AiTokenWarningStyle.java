package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiTokenWarningLevel;
import javafx.scene.paint.Color;

/**
 * Text colours for the token-budget warnings in the AI profile lists.
 *
 * <p>Those lists are painted by whatever theme or app design is active, so a profile that is not
 * near its budget keeps the surface's own cell colour instead of a hard-coded one — a fixed
 * near-black is unreadable on every dark theme. Only the yellow/red warnings override the colour,
 * and they pick the shade that stays legible on the surface the list is drawn on.</p>
 */
final class AiTokenWarningStyle {

    private static final String YELLOW_ON_DARK = "#e3a008";
    private static final String RED_ON_DARK = "#ff7b72";
    private static final String YELLOW_ON_LIGHT = "#8a5a00";
    private static final String RED_ON_LIGHT = "#c53030";

    private AiTokenWarningStyle() {
    }

    /**
     * @return the inline style for a profile list cell, empty when the surface's own text colour
     *     should stand (i.e. for {@link AiTokenWarningLevel#NONE}).
     */
    static String listCellStyle(AiTokenWarningLevel level) {
        return listCellStyle(level, surfaceBackgroundColor());
    }

    /** Same, for an explicitly known list background — the seam the contrast test drives. */
    static String listCellStyle(AiTokenWarningLevel level, String backgroundColor) {
        if (level == null || level == AiTokenWarningLevel.NONE) {
            return "";
        }
        boolean dark = isDark(backgroundColor);
        String color = level == AiTokenWarningLevel.RED
            ? (dark ? RED_ON_DARK : RED_ON_LIGHT)
            : (dark ? YELLOW_ON_DARK : YELLOW_ON_LIGHT);
        return "-fx-text-fill: " + color + ";";
    }

    /** @return the background the profile list is drawn on, or {@code null} when nothing is known. */
    private static String surfaceBackgroundColor() {
        try {
            if (AppDesignStyleSupport.isCustomAppDesignActive()) {
                // A custom design owns the dialog chrome; the terminal colours never reach the list.
                return AppDesignStyleSupport.activeBackgroundColor();
            }
            ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
            return colors != null ? colors.backgroundColor() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isDark(String hex) {
        if (hex == null || hex.isBlank()) {
            return true; // korTTY's own chrome is dark; assume that rather than the unreadable case
        }
        try {
            Color color = Color.web(hex);
            double luminance = 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
            return luminance < 0.5;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
