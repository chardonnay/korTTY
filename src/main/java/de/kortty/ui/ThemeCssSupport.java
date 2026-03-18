package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ThemeManager;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the active application theme and exposes a reusable stylesheet for windows and dialogs.
 */
final class ThemeCssSupport {

    private static final Logger logger = LoggerFactory.getLogger(ThemeCssSupport.class);
    private static final Map<String, String> DYNAMIC_STYLESHEET_CACHE = new ConcurrentHashMap<>();

    private ThemeCssSupport() {
    }

    static ThemeColors resolveThemeColors(KorTTYApplication app) {
        if (app == null || app.getGlobalSettingsManager() == null) {
            return null;
        }
        return resolveThemeColors(app.getGlobalSettingsManager().getSettings(), app.getThemeManager());
    }

    static ThemeColors resolveThemeColors(GlobalSettings settings, ThemeManager themeManager) {
        if (settings == null || settings.getDefaultTerminalSettings() == null) {
            return null;
        }

        ConnectionSettings resolvedSettings = new ConnectionSettings(settings.getDefaultTerminalSettings());
        String themeId = resolvedSettings.getThemeId();
        if (themeId != null && !themeId.isBlank() && themeManager != null) {
            resolvedSettings = themeManager.resolveSettings(resolvedSettings, themeId);
        }

        String backgroundColor = normalizeColorOrDefault(resolvedSettings.getBackgroundColor(), "#1f2933");
        String foregroundColor = normalizeColorOrDefault(resolvedSettings.getForegroundColor(), "#d9e2ec");
        return new ThemeColors(backgroundColor, foregroundColor);
    }

    static String getDynamicStylesheetUrl(ThemeColors colors) {
        if (colors == null) {
            return null;
        }
        return getDynamicStylesheetUrl(colors.backgroundColor(), colors.foregroundColor());
    }

    static String getDynamicStylesheetUrl(String backgroundColor, String foregroundColor) {
        if (backgroundColor == null || backgroundColor.isBlank()) {
            return null;
        }

        String normalizedBackground = normalizeColorOrDefault(backgroundColor, "#1f2933");
        String normalizedForeground = normalizeColorOrDefault(foregroundColor, "#d9e2ec");
        String cacheKey = normalizedBackground + "|" + normalizedForeground;

        return DYNAMIC_STYLESHEET_CACHE.computeIfAbsent(cacheKey, ignored -> {
            try {
                Path tempCss = Files.createTempFile("kortty-theme-", ".css");
                tempCss.toFile().deleteOnExit();
                Files.writeString(tempCss, buildCss(normalizedBackground, normalizedForeground));
                return tempCss.toUri().toString();
            } catch (Exception e) {
                logger.debug("Could not create dynamic theme stylesheet: {}", e.getMessage());
                return null;
            }
        });
    }

    static String buildCss(String backgroundColor, String foregroundColor) {
        String bg = normalizeColorOrDefault(backgroundColor, "#1f2933");
        String fg = normalizeColorOrDefault(foregroundColor, "#d9e2ec");

        Color bgColor = Color.web(bg);
        Color fgColor = Color.web(fg);
        double luminance = 0.299 * bgColor.getRed() + 0.587 * bgColor.getGreen() + 0.114 * bgColor.getBlue();
        Color blendTarget = luminance < 0.5 ? Color.WHITE : Color.BLACK;

        String bgAlt = toHex(bgColor.interpolate(blendTarget, 0.08));
        String bgHover = toHex(bgColor.interpolate(blendTarget, 0.15));
        String bgHoverSub = toHex(bgColor.interpolate(blendTarget, 0.11));
        String fgBright = toHex(luminance < 0.5
            ? fgColor.interpolate(Color.WHITE, 0.3)
            : fgColor.interpolate(Color.BLACK, 0.3));
        String border = toHex(bgColor.interpolate(blendTarget, 0.20));
        String accent = "#0066cc";
        String accentHover = "#0077dd";

        return String.join("\n",
            ".root { -fx-background-color: " + bg + "; }",
            ".label { -fx-text-fill: " + fg + "; }",
            ".menu-bar { -fx-background-color: " + bgAlt + "; }",
            ".menu-bar .menu .label { -fx-text-fill: " + fg + "; }",
            ".menu-bar .menu:hover, .menu-bar .menu:showing { -fx-background-color: " + bgHover + "; }",
            ".context-menu { -fx-background-color: " + bgAlt + "; -fx-padding: 5; }",
            ".menu-item { -fx-background-color: transparent; }",
            ".menu-item .label { -fx-text-fill: " + fg + "; }",
            ".menu-item:hover, .menu-item:focused { -fx-background-color: " + bgHover + "; }",
            ".separator:horizontal .line { -fx-border-color: " + border + "; }",
            ".tab-pane { -fx-background-color: " + bg + "; }",
            ".tab-pane:focused { -fx-background-color: " + bg + "; }",
            ".tab-pane .tab-header-area { -fx-background-color: " + bgAlt + "; }",
            ".tab-pane .tab-header-background { -fx-background-color: " + bgAlt + "; }",
            ".tab { -fx-background-color: " + bgAlt + "; }",
            ".tab:selected { -fx-background-color: " + bg + "; }",
            ".tab .tab-label { -fx-text-fill: " + fg + "; }",
            ".tab:selected .tab-label { -fx-text-fill: " + fgBright + "; }",
            ".tab-close-button { -fx-background-color: " + border + "; }",
            ".scroll-pane { -fx-background-color: " + bg + "; -fx-background: " + bg + "; }",
            ".scroll-pane .viewport { -fx-background-color: " + bg + "; }",
            ".scroll-bar { -fx-background-color: " + bgAlt + "; }",
            ".scroll-bar .thumb { -fx-background-color: " + border + "; }",
            ".scroll-bar .thumb:hover { -fx-background-color: " + bgHover + "; }",
            ".text-flow { -fx-background-color: " + bg + "; }",
            ".button { -fx-background-color: " + bgHover + "; -fx-text-fill: " + fg + "; }",
            ".button:hover { -fx-background-color: " + border + "; }",
            ".button:pressed { -fx-background-color: " + bgAlt + "; }",
            ".button:default { -fx-background-color: " + accent + "; -fx-text-fill: #ffffff; }",
            ".button:default:hover { -fx-background-color: " + accentHover + "; }",
            ".text-field, .password-field { -fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-border-color: " + border + "; }",
            ".text-field:focused, .password-field:focused { -fx-border-color: " + accent + "; }",
            ".text-area { -fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; }",
            ".text-area .content { -fx-background-color: " + bg + "; }",
            ".tree-view { -fx-background-color: " + bg + "; }",
            ".tree-cell { -fx-background-color: transparent; -fx-text-fill: " + fg + "; }",
            ".tree-cell .tree-disclosure-node { -fx-background-color: transparent; }",
            ".tree-cell .tree-disclosure-node .arrow { -fx-background-color: " + fg + "; }",
            ".tree-cell:expanded .tree-disclosure-node .arrow { -fx-background-color: " + fg + "; }",
            ".tree-cell:selected { -fx-background-color: " + bgHover + "; }",
            ".tree-cell:hover { -fx-background-color: " + bgAlt + "; }",
            ".table-view { -fx-background-color: " + bgAlt + "; }",
            ".table-view .column-header { -fx-background-color: " + bgHover + "; }",
            ".table-view .column-header .label { -fx-text-fill: " + fg + "; }",
            ".table-row-cell { -fx-background-color: " + bgAlt + "; }",
            ".table-row-cell:selected { -fx-background-color: " + bgHover + "; }",
            ".table-row-cell:hover { -fx-background-color: " + bgHoverSub + "; }",
            ".table-cell { -fx-text-fill: " + fg + "; }",
            ".list-view { -fx-background-color: " + bg + "; }",
            ".list-cell { -fx-background-color: transparent; -fx-text-fill: " + fg + "; }",
            ".list-cell:selected { -fx-background-color: " + bgHover + "; }",
            ".list-cell:hover { -fx-background-color: " + bgAlt + "; }",
            ".check-box { -fx-text-fill: " + fg + "; }",
            ".check-box .box { -fx-background-color: " + bg + "; -fx-border-color: " + border + "; }",
            ".check-box:selected .mark { -fx-background-color: " + fg + "; }",
            ".radio-button { -fx-text-fill: " + fg + "; }",
            ".radio-button .radio { -fx-background-color: " + bg + "; -fx-border-color: " + border + "; }",
            ".radio-button:selected .dot { -fx-background-color: " + fg + "; }",
            ".combo-box { -fx-background-color: " + bgHover + "; }",
            ".combo-box .list-cell { -fx-text-fill: " + fg + "; -fx-background-color: transparent; }",
            ".combo-box-popup .list-view { -fx-background-color: " + bgAlt + "; }",
            ".combo-box-popup .list-cell:hover { -fx-background-color: " + bgHover + "; }",
            ".spinner { -fx-background-color: " + bg + "; }",
            ".spinner .text-field { -fx-background-color: " + bg + "; }",
            ".spinner .increment-arrow-button, .spinner .decrement-arrow-button { -fx-background-color: " + bgHover + "; }",
            ".progress-bar { -fx-background-color: " + bg + "; }",
            ".progress-bar .track { -fx-background-color: " + bg + "; }",
            ".progress-bar .bar { -fx-background-color: " + accent + "; }",
            ".split-pane { -fx-background-color: " + bg + "; }",
            ".split-pane-divider { -fx-background-color: " + border + "; }",
            ".dialog-pane { -fx-background-color: " + bgAlt + "; }",
            ".dialog-pane .header-panel { -fx-background-color: " + bgHover + "; }",
            ".dialog-pane .content { -fx-background-color: " + bgAlt + "; }",
            ".color-picker { -fx-background-color: " + bgHover + "; }",
            ".color-picker .label { -fx-text-fill: " + fg + "; }",
            ".tooltip { -fx-background-color: " + bgHover + "; -fx-text-fill: " + fg + "; }",
            ".titled-pane > .title { -fx-background-color: " + bgAlt + "; }",
            ".titled-pane > .content { -fx-background-color: " + bg + "; }"
        );
    }

    private static String normalizeColorOrDefault(String color, String fallback) {
        return color != null && !color.isBlank() ? color : fallback;
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
            (int) (color.getRed() * 255),
            (int) (color.getGreen() * 255),
            (int) (color.getBlue() * 255));
    }

    record ThemeColors(String backgroundColor, String foregroundColor) {
    }
}
