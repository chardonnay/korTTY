package de.kortty.ui;

import de.kortty.core.SnippetEditorProfileSupport;
import de.kortty.model.SnippetEditorProfile;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shared editor appearance settings for Monaco-backed editor panes.
 */
public final class EditorSettingsHelper {

    private static final Logger logger = LoggerFactory.getLogger(EditorSettingsHelper.class);

    private static final String DEFAULT_FONT_FAMILY = "Monospaced";
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final String DEFAULT_FOREGROUND = "#d4d4d4";
    private static final String DEFAULT_BACKGROUND = "#1e1e1e";
    private static final String DEFAULT_CURSOR_STYLE = "BLOCK";
    private static final String DEFAULT_CURSOR_COLOR = "#FF0000";

    private EditorSettingsHelper() {
    }

    public record Settings(
        String fontFamily,
        int fontSize,
        String foregroundColor,
        String backgroundColor,
        String cursorStyle,
        String cursorColor
    ) {
    }

    public static Settings loadSettings() {
        String fontFamily = DEFAULT_FONT_FAMILY;
        int fontSize = DEFAULT_FONT_SIZE;
        String foreground = DEFAULT_FOREGROUND;
        String background = DEFAULT_BACKGROUND;
        String cursorStyle = DEFAULT_CURSOR_STYLE;
        String cursorColor = DEFAULT_CURSOR_COLOR;

        try {
            var gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm.getSettings();
            if (gs != null) {
                var term = gs.getDefaultTerminalSettings();
                if (term != null) {
                    if (term.getFontFamily() != null && !term.getFontFamily().isEmpty()) {
                        fontFamily = term.getFontFamily();
                    }
                    if (term.getForegroundColor() != null && !term.getForegroundColor().isEmpty()) {
                        foreground = term.getForegroundColor();
                    }
                    if (term.getBackgroundColor() != null && !term.getBackgroundColor().isEmpty()) {
                        background = term.getBackgroundColor();
                    }
                    if (term.getFontSize() > 0) {
                        fontSize = term.getFontSize();
                    }
                }
                if (gs.getEditorCursorStyle() != null && !gs.getEditorCursorStyle().isEmpty()) {
                    cursorStyle = gs.getEditorCursorStyle();
                }
                if (gs.getEditorCursorColor() != null && !gs.getEditorCursorColor().isEmpty()) {
                    cursorColor = gs.getEditorCursorColor();
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Could not load editor settings, using defaults", e);
        }

        return new Settings(fontFamily, fontSize, foreground, background, cursorStyle, cursorColor);
    }

    public static Settings loadSnippetSettings() {
        Settings base = loadSettings();
        String fontFamily = base.fontFamily();
        int fontSize = base.fontSize();
        String foreground = base.foregroundColor();
        String background = base.backgroundColor();
        String cursorStyle = base.cursorStyle();
        String cursorColor = base.cursorColor();

        try {
            var gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm.getSettings();
            if (gs != null) {
                if (gs.getSnippetFontFamily() != null && !gs.getSnippetFontFamily().isEmpty()) {
                    fontFamily = gs.getSnippetFontFamily();
                }
                if (gs.getSnippetFontSize() != null && gs.getSnippetFontSize() > 0) {
                    fontSize = gs.getSnippetFontSize();
                }
                if (gs.getSnippetForegroundColor() != null && !gs.getSnippetForegroundColor().isEmpty()) {
                    foreground = gs.getSnippetForegroundColor();
                }
                if (gs.getSnippetBackgroundColor() != null && !gs.getSnippetBackgroundColor().isEmpty()) {
                    background = gs.getSnippetBackgroundColor();
                }
                if (gs.getSnippetCursorStyle() != null && !gs.getSnippetCursorStyle().isEmpty()) {
                    cursorStyle = gs.getSnippetCursorStyle();
                }
                if (gs.getSnippetCursorColor() != null && !gs.getSnippetCursorColor().isEmpty()) {
                    cursorColor = gs.getSnippetCursorColor();
                }
                SnippetEditorProfile fallbackProfile = SnippetEditorProfileSupport.fromCurrentSettings(
                    foreground,
                    background,
                    cursorStyle,
                    cursorColor);
                SnippetEditorProfile activeProfile = SnippetEditorProfileSupport.resolveActiveProfile(gs, fallbackProfile);
                foreground = activeProfile.getForegroundColor();
                background = activeProfile.getBackgroundColor();
                cursorStyle = activeProfile.getCursorStyle();
                cursorColor = activeProfile.getCursorColor();
            }
        } catch (RuntimeException e) {
            logger.warn("Could not load snippet editor settings, using editor defaults", e);
        }

        return new Settings(fontFamily, fontSize, foreground, background, cursorStyle, cursorColor);
    }

    public static void applyStyle(MonacoEditorPane area, Settings settings) {
        if (area == null || settings == null) {
            return;
        }
        area.setFont(settings.fontFamily(), settings.fontSize());
        area.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
        area.setCursorStyle(settings.cursorStyle(), settings.cursorColor());
        area.setStyle("-fx-background-color: " + settings.backgroundColor() + ";");
    }

    public static void applyCaretStyle(MonacoEditorPane area, Settings settings) {
        applyStyle(area, settings);
    }

    public static void installPersistentCaretStyling(MonacoEditorPane area, Settings settings) {
        applyStyle(area, settings);
    }

    public static void installPersistentCaretStyling(MonacoEditorPane area, Supplier<Settings> settingsSupplier) {
        if (settingsSupplier != null) {
            applyStyle(area, settingsSupplier.get());
        }
    }

    public static void refreshCaretStyling(MonacoEditorPane area, Settings settings) {
        applyStyle(area, settings);
    }

    public static void applyLineNumbers(MonacoEditorPane area, boolean show, Settings settings) {
        if (area != null) {
            area.setLineNumbers(show);
            applyStyle(area, settings);
        }
    }

    public static MonacoEditorPane createScrollPane(MonacoEditorPane area) {
        return area;
    }

    public static String getEditorFontStyle(Settings settings) {
        return String.format(
            "-fx-font-size: %dpt; -fx-font-family: '%s', 'Consolas', 'Monaco', 'Courier New', monospace;",
            settings.fontSize(), settings.fontFamily());
    }

    public static String getPlainTextStyle(Settings settings) {
        return "-fx-fill: " + settings.foregroundColor() + ";";
    }

    public static Color parseColor(String web, Color fallback) {
        if (web == null || web.isBlank()) {
            return fallback;
        }
        try {
            return Color.web(web.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String cssColorLiteral(String web, Color fallback) {
        if (web != null && !web.isBlank()) {
            try {
                Color.web(web.trim());
                return web.trim();
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        Color c = fallback != null ? fallback : Color.web(DEFAULT_BACKGROUND);
        return String.format(Locale.US, "rgb(%d,%d,%d)",
            Math.round(c.getRed() * 255.0),
            Math.round(c.getGreen() * 255.0),
            Math.round(c.getBlue() * 255.0));
    }
}
