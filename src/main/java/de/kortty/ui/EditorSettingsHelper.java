package de.kortty.ui;

import javafx.application.Platform;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared utility for loading editor appearance settings from GlobalSettings
 * and applying them to InlineCssTextArea instances (FileEditorTab, SnippetEditDialog, SnippetManagementDialog).
 */
public final class EditorSettingsHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(EditorSettingsHelper.class);
    
    // Defaults
    private static final String DEFAULT_FONT_FAMILY = "Monospaced";
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final String DEFAULT_FOREGROUND = "#d4d4d4";
    private static final String DEFAULT_BACKGROUND = "#1e1e1e";
    private static final String DEFAULT_CURSOR_STYLE = "BLOCK";
    private static final String DEFAULT_CURSOR_COLOR = "#FF0000";
    
    private EditorSettingsHelper() {}
    
    /**
     * Immutable record holding all editor display settings.
     */
    public record Settings(
            String fontFamily,
            int fontSize,
            String foregroundColor,
            String backgroundColor,
            String cursorStyle,
            String cursorColor
    ) {}
    
    /**
     * Loads editor settings from GlobalSettings.
     * Falls back to sensible defaults if settings are unavailable.
     */
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
                String loadedStyle = gs.getEditorCursorStyle();
                String loadedColor = gs.getEditorCursorColor();
                if (loadedStyle != null && !loadedStyle.isEmpty()) {
                    cursorStyle = loadedStyle;
                }
                if (loadedColor != null && !loadedColor.isEmpty()) {
                    cursorColor = loadedColor;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load editor settings, using defaults", e);
        }
        
        return new Settings(fontFamily, fontSize, foreground, background, cursorStyle, cursorColor);
    }
    
    /**
     * Applies font, colors, and background to an InlineCssTextArea.
     */
    public static void applyStyle(InlineCssTextArea area, Settings settings) {
        String style = String.format(
            "-fx-font-size: %dpt; " +
            "-fx-font-family: '%s', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-background-color: %s; " +
            "-fx-control-inner-background: %s;",
            settings.fontSize(), settings.fontFamily(),
            settings.backgroundColor(), settings.backgroundColor()
        );
        area.setStyle(style);
        
        applyCaretStyle(area, settings);
    }
    
    /**
     * Applies caret (cursor) color and width to an InlineCssTextArea.
     */
    public static void applyCaretStyle(InlineCssTextArea area, Settings settings) {
        double strokeWidth = switch (settings.cursorStyle().toUpperCase()) {
            case "LINE" -> 1.0;
            case "UNDERSCORE" -> 1.5;
            default -> 3.0; // BLOCK
        };
        
        final double finalWidth = strokeWidth;
        final String color = settings.cursorColor();
        
        Platform.runLater(() -> {
            try {
                // Direct node styling
                area.lookupAll(".caret").forEach(node -> {
                    if (node instanceof javafx.scene.shape.Path caret) {
                        caret.setStroke(javafx.scene.paint.Color.web(color));
                        caret.setStrokeWidth(finalWidth);
                    }
                });
                
                // CSS fallback
                String caretCss = String.format(Locale.US,
                    ".caret { -fx-stroke: %s; -fx-stroke-width: %.1f; }",
                    color, finalWidth
                );
                area.getStylesheets().removeIf(s -> s.startsWith("data:"));
                String dataUri = "data:text/css;charset=utf-8," + URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
                area.getStylesheets().add(dataUri);
            } catch (Exception e) {
                logger.warn("Failed to apply caret style", e);
            }
        });
    }
    
    /**
     * Returns the inline CSS style for plain (non-highlighted) text using the foreground color.
     */
    public static String getPlainTextStyle(Settings settings) {
        return "-fx-fill: " + settings.foregroundColor() + ";";
    }
}
