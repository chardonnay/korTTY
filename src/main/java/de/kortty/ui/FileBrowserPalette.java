package de.kortty.ui;

import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * The {@code -kortty-fb-*} tokens of filebrowser.css for a korTTY design of its own (Gruvbox,
 * Nord, ...), derived from the design's four palette colors: rows, hover, selection, borders and
 * the header strip are blends of them, so every design gets a matching listing without a block
 * of its own in each design stylesheet.
 */
final class FileBrowserPalette {

    private FileBrowserPalette() {
    }

    /** An inline style that sets every token and the panel background; colors in any CSS form JavaFX reads. */
    static String tokenStyle(String background, String text, String dim, String accent) {
        Color bg = Color.web(background);
        Color fg = Color.web(text);
        Color accentColor = Color.web(accent);
        return "-kortty-fb-bg: " + css(bg) + ";"
            + " -kortty-fb-fg: " + css(fg) + ";"
            + " -kortty-fb-dim: " + css(Color.web(dim)) + ";"
            + " -kortty-fb-accent: " + css(accentColor) + ";"
            + " -kortty-fb-border: " + css(bg.interpolate(fg, 0.18)) + ";"
            + " -kortty-fb-hover: " + css(bg.interpolate(fg, 0.07)) + ";"
            + " -kortty-fb-selected: " + css(bg.interpolate(accentColor, 0.28)) + ";"
            + " -kortty-fb-selected-fg: " + css(fg) + ";"
            + " -kortty-fb-status-bg: " + css(bg.interpolate(Color.BLACK, 0.2)) + ";"
            + " -fx-background-color: -kortty-fb-bg;";
    }

    static String css(Color color) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
            Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
            Math.round(color.getBlue() * 255), color.getOpacity());
    }
}
