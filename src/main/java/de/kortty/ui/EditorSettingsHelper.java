package de.kortty.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.Window;
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
     * Loads editor settings from GlobalSettings (terminal + editor defaults).
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
     * Loads snippet-editor-specific settings from GlobalSettings.
     * Falls back to the general editor/terminal defaults when snippet-specific values are not set.
     */
    public static Settings loadSnippetSettings() {
        // Start with general editor settings as base
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
                // Override with snippet-specific settings if present
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
            }
        } catch (Exception e) {
            logger.warn("Could not load snippet editor settings, using editor defaults", e);
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
        
        // Apply caret CSS immediately (will take effect once scene is available)
        applyCaretCss(area, settings);
    }
    
    /**
     * Applies caret (cursor) color and stroke width via CSS stylesheet.
     * Also schedules a deferred direct-node styling pass for after the scene is ready.
     */
    public static void applyCaretStyle(InlineCssTextArea area, Settings settings) {
        applyCaretCss(area, settings);
        applyCaretDirect(area, settings);
    }
    
    /**
     * Applies caret CSS stylesheet. This works even before the scene is shown
     * because stylesheets are evaluated when the node enters the scene.
     */
    private static void applyCaretCss(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle());
        String color = settings.cursorColor();
        
        String caretCss = String.format(Locale.US,
            ".caret { -fx-stroke: %s; -fx-stroke-width: %.1f; }",
            color, strokeWidth
        );
        area.getStylesheets().removeIf(s -> s.startsWith("data:"));
        String dataUri = "data:text/css;charset=utf-8," + URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
        area.getStylesheets().add(dataUri);
    }
    
    /**
     * Directly styles the caret Path node. Deferred until the node is part of a scene.
     */
    private static void applyCaretDirect(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle());
        String color = settings.cursorColor();
        
        Runnable styler = () -> {
            try {
                area.lookupAll(".caret").forEach(node -> {
                    if (node instanceof javafx.scene.shape.Path caret) {
                        caret.setStroke(javafx.scene.paint.Color.web(color));
                        caret.setStrokeWidth(strokeWidth);
                    }
                });
            } catch (Exception e) {
                logger.debug("Caret styling skipped: {}", e.getMessage());
            }
        };
        
        // If the area is already in a scene, apply now + one deferred pass
        if (area.getScene() != null) {
            Platform.runLater(styler);
        }
        
        // Also apply whenever the area enters a scene (covers dialog.showAndWait())
        area.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                // Double-deferred: first runLater lets the scene layout, second applies style
                Platform.runLater(() -> Platform.runLater(styler));
            }
        });
    }
    
    /**
     * Schedules caret styling to be applied when the given window is shown.
     * Use this for dialogs where the InlineCssTextArea caret is created only after showing.
     */
    public static void applyCaretOnShown(Window window, InlineCssTextArea area, Settings settings) {
        window.setOnShown(event -> {
            Platform.runLater(() -> Platform.runLater(() -> applyCaretDirect(area, settings)));
        });
    }
    
    /**
     * Returns the inline CSS style for plain (non-highlighted) text using the foreground color.
     */
    public static String getPlainTextStyle(Settings settings) {
        return "-fx-fill: " + settings.foregroundColor() + ";";
    }
    
    private static double caretStrokeWidth(String cursorStyle) {
        return switch (cursorStyle != null ? cursorStyle.toUpperCase() : "BLOCK") {
            case "LINE" -> 1.0;
            case "UNDERSCORE" -> 1.5;
            default -> 3.0; // BLOCK
        };
    }
}
