package de.kortty.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.reactfx.collection.LiveList;
import org.reactfx.value.Val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

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
     * Re-applies caret styling immediately.
     * Useful when RichTextFX recreates the caret node during typing.
     */
    public static void refreshCaretStyling(InlineCssTextArea area, Settings settings) {
        applyCaretCss(area, settings);
        styleCaretNodesWithRetry(area, settings, 8);
    }
    
    /**
     * Creates a VirtualizedScrollPane wrapping the given InlineCssTextArea
     * with always-visible, dark-themed scrollbars.
     * Uses explicit min size for scrollbars so they are visible on macOS (where
     * overlay scrollbars otherwise get 0 preferred size).
     */
    public static VirtualizedScrollPane<InlineCssTextArea> createScrollPane(InlineCssTextArea area) {
        VirtualizedScrollPane<InlineCssTextArea> scrollPane = new VirtualizedScrollPane<>(
                area,
                ScrollPane.ScrollBarPolicy.AS_NEEDED,
                ScrollPane.ScrollBarPolicy.ALWAYS
        );
        scrollPane.getStyleClass().add("snippet-scroll-pane");
        
        // Load scrollbar CSS from resource so scrollbars have fixed size and dark theme (visible on macOS)
        try {
            java.net.URL url = EditorSettingsHelper.class.getResource("/styles/snippet-scrollbars.css");
            if (url != null) {
                scrollPane.getStylesheets().add(url.toExternalForm());
            }
        } catch (Exception e) {
            logger.warn("Could not load snippet-scrollbars.css, scrollbars may be invisible", e);
        }
        
        return scrollPane;
    }

    /**
     * Shows or hides a RichTextFX line-number gutter for the given area (snippet editor / preview).
     * The gutter uses a fixed width so drag-selection and auto-scroll do not shift the text layout.
     */
    public static void applyLineNumbers(InlineCssTextArea area, boolean show, Settings settings) {
        if (!show) {
            area.setParagraphGraphicFactory(null);
            return;
        }
        area.setParagraphGraphicFactory(createThemedLineNumberFactory(area, settings));
    }

    private record SnippetGutterPalette(Color editorBg, Color gutterBg, Color numberFg) {}

    private static SnippetGutterPalette snippetGutterPalette(Settings settings) {
        Color editorBg = parseColor(settings.backgroundColor(), Color.web(DEFAULT_BACKGROUND));
        Color gutterBg = Color.web("#252a33");
        Color numberFg = Color.web("#d7dde7");
        return new SnippetGutterPalette(editorBg, gutterBg, numberFg);
    }

    private static IntFunction<Node> createThemedLineNumberFactory(InlineCssTextArea area, Settings settings) {
        SnippetGutterPalette palette = snippetGutterPalette(settings);
        Background gutterBackground = new Background(new BackgroundFill(palette.gutterBg(), CornerRadii.EMPTY, Insets.EMPTY));
        int gutterFontSize = Math.max(8, settings.fontSize() - 1);
        Font gutterFont = Font.font(settings.fontFamily(), gutterFontSize);
        Val<String> paragraphCountText = LiveList.sizeOf(area.getParagraphs()).map(count ->
            String.format("%" + Math.max(3, digitsForParagraphCount(count)) + "s", 0));
        Val<Double> gutterWidth = paragraphCountText.map(sampleText ->
            Math.ceil(computeGutterTextWidth(sampleText, settings.fontFamily(), gutterFontSize) + 16.0));

        return paragraphIndex -> {
            Label label = new Label();
            label.setBackground(gutterBackground);
            label.setTextFill(palette.numberFg());
            label.setFont(gutterFont);
            label.setAlignment(Pos.TOP_RIGHT);
            label.setPadding(new Insets(0.0, 8.0, 0.0, 8.0));
            label.setStyle("-fx-background-color: " + cssColorLiteral(null, palette.gutterBg())
                + "; -fx-text-fill: " + cssColorLiteral(null, palette.numberFg()) + ";");
            label.getStyleClass().add("lineno");

            Val<String> lineText = LiveList.sizeOf(area.getParagraphs())
                .map(count -> String.format("%" + Math.max(3, digitsForParagraphCount(count)) + "d", paragraphIndex + 1));

            label.textProperty().bind(lineText.conditionOnShowing(label));
            label.minWidthProperty().bind(gutterWidth.conditionOnShowing(label));
            label.prefWidthProperty().bind(gutterWidth.conditionOnShowing(label));
            label.maxWidthProperty().bind(gutterWidth.conditionOnShowing(label));
            return label;
        };
    }

    private static int digitsForParagraphCount(int paragraphCount) {
        return String.valueOf(Math.max(1, paragraphCount)).length();
    }

    private static double computeGutterTextWidth(String sampleText, String fontFamily, int fontSize) {
        Text measure = new Text(sampleText);
        measure.setFont(Font.font(fontFamily, fontSize));
        double width = measure.getLayoutBounds().getWidth();
        return width > 0.0 ? width : sampleText.length() * measureCharWidth(fontFamily, fontSize);
    }

    private static double luminance(Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }

    private static Color parseColor(String web, Color fallback) {
        if (web == null || web.isBlank()) {
            return fallback;
        }
        try {
            return Color.web(web.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
    
    /**
     * Returns the inline CSS style fragment for the configured editor font.
     */
    public static String getEditorFontStyle(Settings settings) {
        return String.format(
            "-fx-font-size: %dpt; -fx-font-family: '%s', 'Consolas', 'Monaco', 'Courier New', monospace;",
            settings.fontSize(), settings.fontFamily()
        );
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
     * Applies caret + RichTextFX chrome (paragraph rows / line gutter) via data URI stylesheet.
     * Virtualized scrolling recreates nodes; CSS keeps row backgrounds aligned with the editor theme.
     */
    private static void applyCaretCss(InlineCssTextArea area, Settings settings) {
        double strokeWidth = caretStrokeWidth(settings.cursorStyle(), settings.fontFamily(), settings.fontSize());
        String color = settings.cursorColor();
        SnippetGutterPalette pal = snippetGutterPalette(settings);
        String editorBgLiteral = cssColorLiteral(settings.backgroundColor(), pal.editorBg());
        String gutterLiteral = cssColorLiteral(null, pal.gutterBg());
        String numberLiteral = cssColorLiteral(null, pal.numberFg());
        String caretCss = String.format(Locale.US,
                ".caret { -fx-stroke: %s; -fx-stroke-width: %.1f; -fx-stroke-line-cap: butt; }\n"
                        + ".paragraph-box { -fx-background-color: %s; }\n"
                        + ".lineno { -fx-background-color: %s; -fx-text-fill: %s; -fx-font-size: %dpx; -fx-font-family: '%s', 'Consolas', 'Monaco', 'Courier New', monospace; }\n",
                color, strokeWidth, editorBgLiteral, gutterLiteral, numberLiteral,
                Math.max(8, settings.fontSize() - 1), settings.fontFamily());
        area.getStylesheets().removeIf(s -> s.startsWith("data:"));
        String dataUri = "data:text/css;charset=utf-8," + URLEncoder.encode(caretCss, StandardCharsets.UTF_8);
        area.getStylesheets().add(dataUri);
    }

    /** CSS color literal: use original web string when parseable, else {@code rgb(r,g,b)} from {@code fallback}. */
    private static String cssColorLiteral(String web, Color fallback) {
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
                    // Debug logging intentionally disabled to avoid log flooding while typing/navigation.
                }
            }
        } catch (Exception e) {
            // Debug logging intentionally disabled.
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
                // Debug logging intentionally disabled.
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
