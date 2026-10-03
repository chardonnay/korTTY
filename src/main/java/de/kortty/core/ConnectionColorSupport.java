package de.kortty.core;

import de.kortty.model.ServerConnection;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The color that marks a connection's terminal tabs, for example red for production servers.
 * A connection stores it as {@code #RRGGBB} ({@link ServerConnection#getTabColor()}); everything
 * that shows it reads it through {@link #normalizeHex}, so a value that is not a hex color (the field
 * can arrive from a shared teamwork file) is ignored instead of reaching the UI. Pure: no JavaFX.
 */
public final class ConnectionColorSupport {

    /** The colors the connection editor offers first: red, orange, yellow, green, blue, purple, gray. */
    public static final List<String> PRESETS = List.of(
            "#D32F2F", "#F57C00", "#FBC02D", "#388E3C", "#1976D2", "#7B1FA2", "#616161");

    private static final Pattern SHORT_HEX = Pattern.compile("#[0-9a-fA-F]{3}");
    private static final Pattern LONG_HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /**
     * The color family a tab color is named by in the tab's tooltip and in what a screen reader
     * reads for the color dot, so the color is never the only cue.
     */
    public enum Family {
        RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, GRAY, BLACK, WHITE
    }

    private ConnectionColorSupport() {
    }

    /**
     * {@code value} as an upper-case {@code #RRGGBB}, accepting {@code #RGB} and {@code #RRGGBB}
     * with surrounding blanks; {@code null} for anything else, including color names and CSS.
     */
    public static String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (LONG_HEX.matcher(trimmed).matches()) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        if (SHORT_HEX.matcher(trimmed).matches()) {
            StringBuilder expanded = new StringBuilder("#");
            for (int i = 1; i < 4; i++) {
                char digit = Character.toUpperCase(trimmed.charAt(i));
                expanded.append(digit).append(digit);
            }
            return expanded.toString();
        }
        return null;
    }

    /**
     * The color a terminal tab of {@code tabConnection} shows, as {@code #RRGGBB}, or {@code null}
     * for none. The saved connection with the same id comes first, so a color changed in the
     * Connection Manager reaches tabs that were opened from a copy of it (Quick Connect, a teamwork
     * default login); a tab of a connection that is not saved here (a teamwork connection, an unsaved
     * Quick Connect session) uses its own. An invalid stored value counts as no color.
     *
     * @param savedById looks a saved connection up by id; may return {@code null}
     */
    public static String tabColorOf(ServerConnection tabConnection, Function<String, ServerConnection> savedById) {
        if (tabConnection == null) {
            return null;
        }
        ServerConnection saved = tabConnection.getId() != null && savedById != null
                ? savedById.apply(tabConnection.getId())
                : null;
        return normalizeHex((saved != null ? saved : tabConnection).getTabColor());
    }

    /**
     * The family a color is named by: black for very dark colors, white and gray for colors with
     * hardly any hue, otherwise the hue. {@code null} for a value {@link #normalizeHex} rejects.
     */
    public static Family family(String hex) {
        String normalized = normalizeHex(hex);
        if (normalized == null) {
            return null;
        }
        int rgb = Integer.parseInt(normalized.substring(1), 16);
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double brightness = max;
        double saturation = max == 0 ? 0 : (max - min) / max;
        if (brightness < 0.2) {
            return Family.BLACK;
        }
        if (saturation < 0.15) {
            return brightness > 0.9 ? Family.WHITE : Family.GRAY;
        }
        double hue = hue(r, g, b, max, min);
        if (hue < 15 || hue >= 345) {
            return Family.RED;
        }
        if (hue < 40) {
            return Family.ORANGE;
        }
        if (hue < 70) {
            return Family.YELLOW;
        }
        if (hue < 165) {
            return Family.GREEN;
        }
        if (hue < 195) {
            return Family.CYAN;
        }
        if (hue < 255) {
            return Family.BLUE;
        }
        if (hue < 290) {
            return Family.PURPLE;
        }
        return Family.PINK;
    }

    /** The HSB hue in degrees, 0 (inclusive) to 360 (exclusive); only called with max > min. */
    private static double hue(double r, double g, double b, double max, double min) {
        double delta = max - min;
        double hue;
        if (max == r) {
            hue = 60 * (((g - b) / delta) % 6);
        } else if (max == g) {
            hue = 60 * (((b - r) / delta) + 2);
        } else {
            hue = 60 * (((r - g) / delta) + 4);
        }
        return hue < 0 ? hue + 360 : hue;
    }
}
