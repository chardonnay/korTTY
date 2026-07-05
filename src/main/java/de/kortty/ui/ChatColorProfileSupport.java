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
     * out of the box; the fixed palettes below are derived from the Odysseus reference screenshots.
     * Slot order: background, surface, foreground, muted, accent, border, codeBackground,
     * userBubbleBackground, userBubbleBorder.
     */
    private static final List<ChatColorProfile> BUILT_INS = List.of(
        ChatColorProfile.followTheme(AUTO_ID, null),
        ChatColorProfile.of(
            "original", "Original",
            "#f1ebdf", "#e7dfce", "#35322b", "#8f887a", "#2f8f84", "#d9cfbb", "#e7dfce", "#f6f0e4", "#d9cfbb"),
        ChatColorProfile.of(
            "paper", "Paper",
            "#f7f3ea", "#efe9dc", "#33302a", "#918a7c", "#2f8f84", "#e0d8c6", "#efe9dc", "#fbf7ef", "#e0d8c6"),
        ChatColorProfile.of(
            "midnight", "Midnight",
            "#0d0f14", "#171a21", "#c6cdd6", "#6a7280", "#45b3bd", "#262b34", "#090a0d", "#161922", "#2c313c"),
        ChatColorProfile.of(
            "cyberpunk", "Cyberpunk",
            "#08070d", "#13101c", "#b6e3e6", "#7c6d92", "#c94fd6", "#7a3d9e", "#060509", "#150e1e", "#b64fce"),
        ChatColorProfile.of(
            "retrowave", "Retrowave",
            "#16131f", "#1e1a2c", "#cdc9e0", "#7d7596", "#e0607f", "#57497d", "#0f0d18", "#1c1730", "#6d5399"),
        ChatColorProfile.of(
            "forest", "Forest",
            "#0d130e", "#151d16", "#c6d2c2", "#7e8b78", "#5cb874", "#2c3f2a", "#080c08", "#131d15", "#3a5738"),
        ChatColorProfile.of(
            "ocean", "Ocean",
            "#0a0e15", "#121824", "#c3ccd8", "#6a7688", "#4a90d9", "#26303f", "#070a10", "#121a28", "#2e4159"),
        ChatColorProfile.of(
            "terminal", "Terminal",
            "#030803", "#0a1a0d", "#5fd88a", "#3f7a50", "#4ade80", "#14401f", "#020602", "#08160c", "#1f5a2c"),
        ChatColorProfile.of(
            "gpt", "GPT",
            "#131315", "#1c1c20", "#cdcdce", "#7a7a7d", "#2f9e8f", "#2c2c31", "#0d0d0f", "#1b1b1f", "#34343a"),
        ChatColorProfile.of(
            "cute", "Cute",
            "#fce7ee", "#f6d9e4", "#4a3540", "#a98a97", "#dd5c8e", "#f0c7d6", "#faecf2", "#fdf2f6", "#f0c7d6"));

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
