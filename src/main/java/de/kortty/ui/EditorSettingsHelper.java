package de.kortty.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

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
            }
        } catch (Exception e) {
            logger.warn("Could not load snippet editor settings, using editor defaults", e);
        }
        
        return new Settings(fontFamily, fontSize, foreground, background, cursorStyle, cursorColor);
    }
    
    /**
     * Applies font, colors, and background to an InlineCssTextArea.
     * Also sets up the caret CSS stylesheet.
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
        
        // Apply caret via CSS stylesheet (evaluated when node enters scene)
        applyCaretCss(area, settings);
    }
    
    /**
     * Applies caret (cursor) color and stroke width.
     * Uses CSS stylesheet + direct node styling with retries + focus listener.
     */
    public static void applyCaretStyle(InlineCssTextArea area, Settings settings) {
        applyCaretCss(area, settings);
        styleCaretNodesWithRetry(area, settings, 5);
        installCaretFocusListener(area, settings);
    }
    
    /**
     * Sets up persistent caret styling for dialogs.
     * Call this once during dialog construction to ensure the caret is styled
     * regardless of when RichTextFX creates it internally.
     * Combines: CSS stylesheet + scene listener + focus listener + timed retries.
     */
    public static void installPersistentCaretStyling(InlineCssTextArea area, Settings settings) {
        // 1. CSS stylesheet (works once the node is in a rendered scene)
        applyCaretCss(area, settings);
        
        // 2. Focus listener: re-style every time the area gains focus
        installCaretFocusListener(area, settings);
        
        // 3. Scene listener: when the area enters a scene, start retry styling
        area.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                styleCaretNodesWithRetry(area, settings, 5);
            }
        });
        
        // 4. If already in a scene, start retry now
        if (area.getScene() != null) {
            styleCaretNodesWithRetry(area, settings, 5);
        }
    }
    
    /**
     * Returns the inline CSS style for plain (non-highlighted) text using the foreground color.
     */
    public static String getPlainTextStyle(Settings settings) {
        return "-fx-fill: " + settings.foregroundColor() + ";";
    }
    
    // ---- Internal methods ----
    
    /**
     * Applies caret CSS stylesheet via data URI.
     */
    private static void applyCaretCss(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle());
        String color = settings.cursorColor();
        
        String caretCss = String.format(Locale.US,
            ".caret { -fx-stroke: %s !important; -fx-stroke-width: %.1f !important; }",
            color, strokeWidth
        );
        area.getStylesheets().removeIf(s -> s.startsWith("data:"));
        String dataUri = "data:text/css;charset=utf-8," + URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
        area.getStylesheets().add(dataUri);
    }
    
    /**
     * Directly styles all .caret Path nodes found in the area.
     * Returns true if at least one caret was styled.
     */
    private static boolean styleCaretNodesDirect(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle());
        String color = settings.cursorColor();
        boolean[] styled = {false};
        
        try {
            Set<javafx.scene.Node> carets = area.lookupAll(".caret");
            for (javafx.scene.Node node : carets) {
                if (node instanceof javafx.scene.shape.Path caret) {
                    caret.setStroke(javafx.scene.paint.Color.web(color));
                    caret.setStrokeWidth(strokeWidth);
                    styled[0] = true;
                    logger.debug("Styled caret node: color={}, strokeWidth={}", color, strokeWidth);
                }
            }
        } catch (Exception e) {
            logger.debug("Caret direct styling skipped: {}", e.getMessage());
        }
        
        return styled[0];
    }
    
    /**
     * Tries to style the caret nodes with up to {@code maxRetries} attempts,
     * spacing retries 150ms apart using PauseTransition.
     */
    private static void styleCaretNodesWithRetry(InlineCssTextArea area, Settings settings, int maxRetries) {
        Platform.runLater(() -> {
            if (styleCaretNodesDirect(area, settings)) {
                return; // Success on first try
            }
            if (maxRetries <= 1) return;
            
            // Schedule retries with increasing delays
            retryCaretStyling(area, settings, maxRetries - 1, 150);
        });
    }
    
    private static void retryCaretStyling(InlineCssTextArea area, Settings settings, int retriesLeft, long delayMs) {
        if (retriesLeft <= 0) return;
        
        PauseTransition pause = new PauseTransition(Duration.millis(delayMs));
        pause.setOnFinished(event -> {
            if (styleCaretNodesDirect(area, settings)) {
                logger.debug("Caret styled successfully after retry (remaining={})", retriesLeft);
                return; // Done
            }
            // Retry with slightly longer delay
            retryCaretStyling(area, settings, retriesLeft - 1, delayMs + 100);
        });
        pause.play();
    }
    
    /**
     * Installs a focus listener that re-applies caret styling when the area gains focus.
     * This is a safety net: if the caret node is recreated (e.g. after layout changes),
     * the styling will be re-applied.
     */
    private static void installCaretFocusListener(InlineCssTextArea area, Settings settings) {
        area.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                Platform.runLater(() -> styleCaretNodesDirect(area, settings));
            }
        });
    }
    
    private static double caretStrokeWidth(String cursorStyle) {
        return switch (cursorStyle != null ? cursorStyle.toUpperCase() : "BLOCK") {
            case "LINE" -> 1.0;
            case "UNDERSCORE" -> 1.5;
            default -> 3.0; // BLOCK
        };
    }
}
