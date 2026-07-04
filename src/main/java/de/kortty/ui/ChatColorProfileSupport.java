package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ThemeManager;
import de.kortty.model.ChatColorProfile;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Theme;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of AI-chat color profiles and the resolver that turns a profile into the concrete
 * {@link ThemeCssSupport.ChatPalette} consumed by {@link ThemeCssSupport#buildChatCss}.
 *
 * <p>The first built-in profile {@link #AUTO_ID follows the active terminal theme}: its colors are
 * derived from the theme's agent-panel palette so the chat matches the rest of the app. The other
 * built-ins are fixed palettes; a new profile supplied as a color set is added as one
 * {@link ChatColorProfile#of} entry here.
 */
final class ChatColorProfileSupport {

    static final String AUTO_ID = "auto";

    private static final ChatColorProfile DARK_FALLBACK = ChatColorProfile.of(
        "terminal-dark", "Terminal Dark",
        "#1e2228", "#262b33", "#d7dee8", "#8a94a3", "#4f9cf0", "#333a44", "#14181e", "#243244", "#3b5273");

    /**
     * Built-in profiles, in menu order. {@code AUTO} is first so the chat matches the terminal theme
     * out of the box; the fixed palettes below are starting points that supplied screenshots refine.
     */
    private static final List<ChatColorProfile> BUILT_INS = List.of(
        ChatColorProfile.followTheme(AUTO_ID, null),
        DARK_FALLBACK,
        ChatColorProfile.of(
            "nord", "Nord",
            "#2e3440", "#3b4252", "#eceff4", "#8f9bb0", "#88c0d0", "#434c5e", "#272c36", "#3b4a5c", "#4c6178"),
        ChatColorProfile.of(
            "solarized-dark", "Solarized Dark",
            "#002b36", "#073642", "#93a1a1", "#586e75", "#268bd2", "#0e4b57", "#00212b", "#0a4451", "#1a6b7a"));

    private ChatColorProfileSupport() {
    }

    /** All selectable profiles, in menu order. */
    static List<ChatColorProfile> all() {
        return new ArrayList<>(BUILT_INS);
    }

    /** The display name for a profile, resolving the theme-following profile's name from i18n. */
    static String displayName(ChatColorProfile profile) {
        if (profile == null) {
            return "";
        }
        if (profile.followsTheme() && (profile.name() == null || profile.name().isBlank())) {
            return I18n.get("ai.chat.profile.auto");
        }
        return profile.name() != null ? profile.name() : profile.id();
    }

    /** The profile for {@code id}, or the theme-following default when unknown/blank. */
    static ChatColorProfile byId(String id) {
        if (id != null && !id.isBlank()) {
            for (ChatColorProfile profile : BUILT_INS) {
                if (profile.id().equals(id)) {
                    return profile;
                }
            }
        }
        return BUILT_INS.get(0);
    }

    /** The currently selected profile, read from global settings. */
    static ChatColorProfile activeProfile(KorTTYApplication app) {
        String id = null;
        if (app != null && app.getGlobalSettingsManager() != null
            && app.getGlobalSettingsManager().getSettings() != null) {
            id = app.getGlobalSettingsManager().getSettings().getChatColorProfileId();
        }
        return byId(id);
    }

    /** Resolves the concrete palette used to build the chat stylesheet. */
    static ThemeCssSupport.ChatPalette resolvePalette(ChatColorProfile profile, KorTTYApplication app) {
        if (profile == null) {
            profile = BUILT_INS.get(0);
        }
        if (!profile.followsTheme()) {
            return new ThemeCssSupport.ChatPalette(
                profile.background(), profile.surface(), profile.foreground(), profile.muted(),
                profile.accent(), profile.border(), profile.codeBackground(),
                profile.userBubbleBackground(), profile.userBubbleBorder());
        }
        return deriveFromTheme(app);
    }

    /** Palette derived from the active terminal theme's colors and agent-panel accents. */
    private static ThemeCssSupport.ChatPalette deriveFromTheme(KorTTYApplication app) {
        Theme theme = resolveActiveTheme(app);
        if (theme == null) {
            return resolvePalette(DARK_FALLBACK, app);
        }
        ThemeCssSupport.AgentActivityColors agent = ThemeCssSupport.resolveAgentActivityColors(theme);
        String background = safeColor(theme.getBackgroundColor(), DARK_FALLBACK.background());
        String foreground = safeColor(theme.getForegroundColor(), DARK_FALLBACK.foreground());
        String accent = agent.accentColor();

        Color bgColor = Color.web(background);
        Color accentColor = Color.web(accent);
        String codeBackground = ThemeCssSupport.toHex(bgColor.interpolate(Color.BLACK, 0.25));
        String userBubbleBackground = ThemeCssSupport.toHex(bgColor.interpolate(accentColor, 0.16));
        String userBubbleBorder = ThemeCssSupport.toHex(bgColor.interpolate(accentColor, 0.42));

        return new ThemeCssSupport.ChatPalette(
            background,
            agent.backgroundColor(),
            foreground,
            agent.mutedTextColor(),
            accent,
            agent.borderColor(),
            codeBackground,
            userBubbleBackground,
            userBubbleBorder);
    }

    private static Theme resolveActiveTheme(KorTTYApplication app) {
        if (app == null || app.getThemeManager() == null) {
            return null;
        }
        ThemeManager themeManager = app.getThemeManager();
        String themeId = null;
        if (app.getGlobalSettingsManager() != null && app.getGlobalSettingsManager().getSettings() != null) {
            ConnectionSettings defaults = app.getGlobalSettingsManager().getSettings().getDefaultTerminalSettings();
            if (defaults != null) {
                themeId = defaults.getThemeId();
            }
        }
        if (themeId != null && !themeId.isBlank()) {
            return themeManager.getTheme(themeId).orElseGet(themeManager::getDefaultTheme);
        }
        return themeManager.getDefaultTheme();
    }

    private static String safeColor(String color, String fallback) {
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
}
