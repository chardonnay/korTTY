package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ThemeManager;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Theme;
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
    private static final Map<String, String> AGENT_ACTIVITY_STYLESHEET_CACHE = new ConcurrentHashMap<>();

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
        String promptText = toHex(fgColor.interpolate(bgColor, 0.45));
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
            ".text-input { -fx-prompt-text-fill: " + promptText + "; }",
            ".text-field, .password-field { -fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-prompt-text-fill: " + promptText + "; -fx-border-color: " + border + "; }",
            ".text-field:focused, .password-field:focused { -fx-border-color: " + accent + "; }",
            ".text-area { -fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-prompt-text-fill: " + promptText + "; }",
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

    static String getAgentActivityStylesheetUrl(Theme theme) {
        AgentActivityColors colors = resolveAgentActivityColors(theme);
        String cacheKey = String.join("|",
            colors.backgroundColor(),
            colors.borderColor(),
            colors.textColor(),
            colors.mutedTextColor(),
            colors.accentColor(),
            colors.errorColor());
        return AGENT_ACTIVITY_STYLESHEET_CACHE.computeIfAbsent(cacheKey, ignored -> {
            try {
                Path tempCss = Files.createTempFile("kortty-agent-theme-", ".css");
                tempCss.toFile().deleteOnExit();
                Files.writeString(tempCss, buildAgentActivityCss(colors));
                return tempCss.toUri().toString();
            } catch (Exception e) {
                logger.debug("Could not create dynamic terminal-agent stylesheet: {}", e.getMessage());
                return null;
            }
        });
    }

    static String buildAgentActivityCss(Theme theme) {
        return buildAgentActivityCss(resolveAgentActivityColors(theme));
    }

    static AgentActivityColors resolveAgentActivityColors(Theme theme) {
        if (theme == null) {
            return new AgentActivityColors("#052f35", "#1f5961", "#e8f3f2", "#8fb0b4", "#18c26e", "#e36a4d");
        }
        return new AgentActivityColors(
            normalizeColorOrDefault(theme.getAgentPanelBackgroundColor(), "#052f35"),
            normalizeColorOrDefault(theme.getAgentPanelBorderColor(), "#1f5961"),
            normalizeColorOrDefault(theme.getAgentPanelTextColor(), "#e8f3f2"),
            normalizeColorOrDefault(theme.getAgentPanelMutedTextColor(), "#8fb0b4"),
            normalizeColorOrDefault(theme.getAgentPanelAccentColor(), "#18c26e"),
            normalizeColorOrDefault(theme.getAgentPanelErrorColor(), "#e36a4d"));
    }

    private static String buildAgentActivityCss(AgentActivityColors colors) {
        String bg = colors.backgroundColor();
        String border = colors.borderColor();
        String text = colors.textColor();
        String muted = colors.mutedTextColor();
        String accent = colors.accentColor();
        String error = colors.errorColor();

        Color bgColor = Color.web(bg);
        Color textColor = Color.web(text);
        double luminance = 0.299 * bgColor.getRed() + 0.587 * bgColor.getGreen() + 0.114 * bgColor.getBlue();
        Color blendTarget = luminance < 0.5 ? Color.WHITE : Color.BLACK;
        String surface = toHex(bgColor.interpolate(blendTarget, luminance < 0.5 ? 0.08 : 0.04));
        String hover = toHex(bgColor.interpolate(blendTarget, luminance < 0.5 ? 0.14 : 0.08));
        String commandBackground = toHex(bgColor.interpolate(Color.BLACK, luminance < 0.5 ? 0.18 : 0.04));
        String textStrong = toHex(textColor.interpolate(blendTarget, luminance < 0.5 ? 0.16 : 0.10));
        String success = toHex(Color.web("#38a169").interpolate(bgColor, luminance < 0.5 ? 0.10 : 0.0));

        return String.join("\n",
            ".ai-agent-activity-panel { -fx-background-color: " + bg + "; -fx-border-color: " + border + "; }",
            ".ai-agent-activity-title { -fx-text-fill: " + text + "; }",
            ".ai-agent-prompt-viewer { -fx-background-color: " + surface + "; -fx-border-color: " + border + "; -fx-text-fill: " + text + "; -fx-prompt-text-fill: " + muted + "; }",
            ".ai-agent-prompt-viewer .content { -fx-background-color: transparent; }",
            ".ai-agent-prompt-viewer .scroll-pane, .ai-agent-prompt-viewer .scroll-pane .viewport { -fx-background-color: transparent; }",
            ".ai-agent-header-busy-dot { -fx-background-color: " + accent + "; -fx-effect: dropshadow(gaussian, " + accent + ", 10, 0.55, 0, 0); }",
            ".ai-agent-header-busy-label { -fx-text-fill: " + text + "; }",
            ".ai-agent-activity-meta { -fx-text-fill: " + muted + "; }",
            ".ai-agent-resize-handle { -fx-border-color: " + border + "; }",
            ".ai-agent-resize-handle:hover { -fx-background-color: " + hover + "; }",
            ".ai-agent-activity-scroll { -fx-border-color: " + border + "; }",
            ".ai-agent-activity-scroll .viewport { -fx-background-color: transparent; }",
            ".ai-agent-activity-text { -fx-text-fill: " + text + "; }",
            ".ai-agent-detail { -fx-text-fill: " + muted + "; }",
            ".ai-agent-activity-marker { -fx-text-fill: " + muted + "; -fx-font-weight: bold; }",
            ".ai-agent-marker-input { -fx-text-fill: " + accent + "; }",
            ".ai-agent-marker-output { -fx-text-fill: " + success + "; }",
            ".ai-agent-marker-question { -fx-text-fill: " + accent + "; }",
            ".ai-agent-marker-error { -fx-text-fill: " + error + "; }",
            ".ai-agent-marker-cancelled { -fx-text-fill: " + muted + "; }",
            ".ai-agent-marker-info { -fx-text-fill: " + textStrong + "; }",
            ".ai-agent-marker-running { -fx-effect: dropshadow(gaussian, " + accent + ", 8, 0.45, 0, 0); }",
            ".ai-agent-activity-panel .ai-agent-toggle-button, .ai-agent-activity-panel .ai-agent-font-button { -fx-background-color: transparent; -fx-border-color: " + border + "; -fx-text-fill: " + muted + "; }",
            ".ai-agent-activity-panel .ai-agent-toggle-button:hover, .ai-agent-activity-panel .ai-agent-font-button:hover { -fx-background-color: " + hover + "; -fx-text-fill: " + text + "; }",
            ".ai-agent-activity-panel .ai-agent-cancel-button { -fx-background-color: transparent; -fx-border-color: " + border + "; -fx-text-fill: " + muted + "; }",
            ".ai-agent-activity-panel .ai-agent-cancel-button:hover { -fx-background-color: " + hover + "; -fx-border-color: " + accent + "; -fx-text-fill: " + textStrong + "; }",
            ".ai-agent-activity-panel .ai-agent-cancel-button:disabled { -fx-background-color: transparent; -fx-border-color: " + border + "; -fx-text-fill: " + muted + "; }",
            ".ai-agent-activity-panel .ai-agent-collapse-button { -fx-border-color: " + border + "; -fx-text-fill: " + text + "; }",
            ".ai-agent-activity-panel .ai-agent-collapse-button:hover { -fx-background-color: " + hover + "; -fx-text-fill: " + textStrong + "; }",
            ".ai-agent-option-check { -fx-text-fill: " + muted + "; }",
            ".ai-agent-option-check .box { -fx-background-color: transparent; -fx-border-color: " + border + "; }",
            ".ai-agent-option-check:selected .mark { -fx-background-color: " + accent + "; }",
            ".ai-agent-activity-panel .ai-agent-close-button { -fx-background-color: transparent; -fx-border-color: transparent; -fx-text-fill: " + muted + "; }",
            ".ai-agent-activity-panel .ai-agent-close-button:hover { -fx-background-color: " + hover + "; -fx-text-fill: " + text + "; }",
            ".ai-agent-prompt-box { -fx-background-color: " + surface + "; -fx-border-color: " + border + "; }",
            ".ai-agent-prompt-label { -fx-text-fill: " + text + "; }",
            ".ai-agent-command-preview { -fx-background-color: " + commandBackground + "; -fx-border-color: " + border + "; }",
            ".ai-agent-command-line { -fx-text-fill: " + textStrong + "; }",
            ".terminal-agent-busy-overlay { -fx-background-color: " + surface + "; -fx-border-color: " + border + "; }",
            ".terminal-agent-busy-robot { -fx-text-fill: " + accent + "; -fx-effect: dropshadow(gaussian, " + accent + ", 8, 0.35, 0, 0); }",
            ".terminal-agent-busy-text { -fx-text-fill: " + text + "; }"
        );
    }

    private static String normalizeColorOrDefault(String color, String fallback) {
        if (color == null || color.isBlank()) {
            return fallback;
        }
        try {
            Color.web(color.trim());
            return color.trim();
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
            (int) (color.getRed() * 255),
            (int) (color.getGreen() * 255),
            (int) (color.getBlue() * 255));
    }

    record ThemeColors(String backgroundColor, String foregroundColor) {
    }

    record AgentActivityColors(
        String backgroundColor,
        String borderColor,
        String textColor,
        String mutedTextColor,
        String accentColor,
        String errorColor) {
    }
}
