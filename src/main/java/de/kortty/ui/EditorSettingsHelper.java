package de.kortty.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
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
                styleCaretNodesWithRetry(area, settings, 8);
            }
        });
        
        // 4. If already in a scene, start retry now
        if (area.getScene() != null) {
            styleCaretNodesWithRetry(area, settings, 8);
        }
    }
    
    /**
     * Creates a VirtualizedScrollPane wrapping the given InlineCssTextArea
     * with always-visible, dark-themed scrollbars.
     */
    public static VirtualizedScrollPane<InlineCssTextArea> createScrollPane(InlineCssTextArea area) {
        VirtualizedScrollPane<InlineCssTextArea> scrollPane = new VirtualizedScrollPane<>(
                area,
                ScrollPane.ScrollBarPolicy.AS_NEEDED,
                ScrollPane.ScrollBarPolicy.ALWAYS
        );
        
        // Apply dark-themed scrollbar CSS so they are clearly visible on dark backgrounds
        String scrollbarCss = String.join("\n",
            ".scroll-bar {",
            "    -fx-background-color: #2a2a2a;",
            "    -fx-opacity: 1.0;",
            "}",
            ".scroll-bar .thumb {",
            "    -fx-background-color: #666666;",
            "    -fx-background-radius: 3;",
            "}",
            ".scroll-bar:hover .thumb {",
            "    -fx-background-color: #888888;",
            "}",
            ".scroll-bar .track {",
            "    -fx-background-color: #2a2a2a;",
            "}",
            ".scroll-bar .increment-button, .scroll-bar .decrement-button {",
            "    -fx-background-color: #333333;",
            "    -fx-padding: 3;",
            "}",
            ".scroll-bar .increment-arrow, .scroll-bar .decrement-arrow {",
            "    -fx-background-color: #888888;",
            "}"
        );
        scrollPane.getStylesheets().add(
                "data:text/css;charset=utf-8," + URLEncoder.encode(scrollbarCss, StandardCharsets.UTF_8));
        
        return scrollPane;
    }
    
    /**
     * Returns the inline CSS style for plain (non-highlighted) text using the foreground color.
     */
    public static String getPlainTextStyle(Settings settings) {
        return "-fx-fill: " + settings.foregroundColor() + ";";
    }
    
    // ---- Internal methods ----
    
    /**
     * Measures the width of a single character 'M' for the given font.
     * Used to calculate the block cursor width.
     */
    private static double measureCharWidth(String fontFamily, int fontSize) {
        try {
            Text measure = new Text("M");
            measure.setFont(Font.font(fontFamily, fontSize));
            double width = measure.getLayoutBounds().getWidth();
            if (width > 0) {
                return width;
            }
        } catch (Exception e) {
            logger.debug("Could not measure char width: {}", e.getMessage());
        }
        // Fallback: rough estimate for monospaced fonts
        return fontSize * 0.6;
    }
    
    /**
     * Returns the stroke width for the given cursor style.
     * For BLOCK: uses the full character width measured from the font metrics.
     */
    private static double caretStrokeWidth(String cursorStyle, String fontFamily, int fontSize) {
        return switch (cursorStyle != null ? cursorStyle.toUpperCase() : "BLOCK") {
            case "LINE" -> 2.0;
            case "UNDERSCORE" -> 2.0;
            default -> measureCharWidth(fontFamily, fontSize); // BLOCK = full character width
        };
    }
    
    /**
     * Applies caret CSS stylesheet via data URI.
     */
    private static void applyCaretCss(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle(), settings.fontFamily(), settings.fontSize());
        String color = settings.cursorColor();
        
        String caretCss = String.format(Locale.US,
            ".caret { -fx-stroke: %s; -fx-stroke-width: %.1f; -fx-stroke-line-cap: butt; }",
            color, strokeWidth
        );
        area.getStylesheets().removeIf(s -> s.startsWith("data:"));
        String dataUri = "data:text/css;charset=utf-8," + URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
        area.getStylesheets().add(dataUri);
    }
    
    /**
     * Directly styles all .caret Path nodes found in the area.
     * For BLOCK style: sets stroke width to full character width, BUTT line cap,
     * and translates the caret right by half the char width so the block covers
     * the character to the right of the insertion point.
     * Returns true if at least one caret was styled.
     */
    private static boolean styleCaretNodesDirect(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle(), settings.fontFamily(), settings.fontSize());
        String color = settings.cursorColor();
        boolean isBlock = settings.cursorStyle() == null || settings.cursorStyle().equalsIgnoreCase("BLOCK");
        boolean[] styled = {false};
        
        try {
            Set<javafx.scene.Node> carets = area.lookupAll(".caret");
            for (javafx.scene.Node node : carets) {
                if (node instanceof javafx.scene.shape.Path caret) {
                    caret.setStroke(javafx.scene.paint.Color.web(color));
                    caret.setStrokeWidth(strokeWidth);
                    caret.setStrokeLineCap(StrokeLineCap.BUTT);
                    
                    // For block cursor: shift right by half the char width
                    // so the block covers the character to the right of the caret position
                    if (isBlock) {
                        caret.setTranslateX(strokeWidth / 2.0);
                    } else {
                        caret.setTranslateX(0);
                    }
                    
                    styled[0] = true;
                    logger.debug("Styled caret node: color={}, strokeWidth={}, isBlock={}", 
                            color, strokeWidth, isBlock);
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
            retryCaretStyling(area, settings, maxRetries - 1, 100);
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
            // Retry with slightly longer delay (max 500ms)
            retryCaretStyling(area, settings, retriesLeft - 1, Math.min(delayMs + 50, 500));
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
}
