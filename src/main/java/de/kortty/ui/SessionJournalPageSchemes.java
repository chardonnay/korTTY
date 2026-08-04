package de.kortty.ui;

import de.kortty.model.SessionJournalPageScheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The named colour schemes the journal page can be rendered in. Follows the
 * {@link ChatColorProfileSupport} precedent: a small built-in registry plus a resolver, so a new
 * scheme is one list entry rather than a new stylesheet.
 */
public final class SessionJournalPageSchemes {

    /**
     * {@code auto} keeps the page's own dark/light pair and the {@code prefers-color-scheme}
     * behaviour, which is why it carries no colours and stays the default.
     */
    private static final SessionJournalPageScheme AUTO = new SessionJournalPageScheme(
        SessionJournalPageScheme.ID_AUTO, null, true,
        null, null, null, null, null, null, null, null, null, null, null);

    /** Filled in from the active terminal theme when the page is rendered. */
    private static final SessionJournalPageScheme THEME = new SessionJournalPageScheme(
        SessionJournalPageScheme.ID_THEME, null, true,
        null, null, null, null, null, null, null, null, null, null, null);

    private static final List<SessionJournalPageScheme> FIXED = List.of(
        new SessionJournalPageScheme("paper", "Paper", false,
            "#f7f3ea", "#fffdf8", "#efe9dc", "#ded5c4", "#2c2a26", "#6b6558", "#8a5a1f",
            "#3f6b2f", "#2a4a6b", "#f2cc60", "#ff9f43"),
        new SessionJournalPageScheme("midnight", "Midnight", false,
            "#0b1020", "#141a2e", "#1b2340", "#2a3358", "#dbe2f5", "#8891b4", "#7aa2f7",
            "#9ece6a", "#7dcfff", "#e0af68", "#ff9e64"),
        new SessionJournalPageScheme("ocean", "Ocean", false,
            "#0d1f26", "#123039", "#17404c", "#1f5866", "#d5e9ef", "#82a6b1", "#3fb8c6",
            "#7fd1a5", "#8fc7e8", "#f0d066", "#ff9f43"),
        new SessionJournalPageScheme("forest", "Forest", false,
            "#101a12", "#17261a", "#1e3223", "#2a4530", "#dceadf", "#8aa791", "#5fb87a",
            "#a6d98b", "#8fc7a8", "#e8cf6a", "#ff9f43"),
        new SessionJournalPageScheme("retrowave", "Retrowave", false,
            "#1a1030", "#241542", "#2f1c55", "#432a75", "#f2e7ff", "#a693c9", "#ff6ec7",
            "#7ef0d0", "#8ab4ff", "#ffd166", "#ff5f9e"),
        new SessionJournalPageScheme("high-contrast", "High contrast", false,
            "#000000", "#0a0a0a", "#141414", "#666666", "#ffffff", "#cccccc", "#00d4ff",
            "#00ff66", "#66ccff", "#ffee00", "#ff8800"));

    private SessionJournalPageSchemes() {
    }

    /** Every scheme in menu order; the two derived ones first. */
    public static List<SessionJournalPageScheme> all() {
        List<SessionJournalPageScheme> schemes = new ArrayList<>(FIXED.size() + 2);
        schemes.add(AUTO);
        schemes.add(THEME);
        schemes.addAll(FIXED);
        return schemes;
    }

    /** Resolves an id; an unknown one falls back to {@code auto} rather than to nothing. */
    public static SessionJournalPageScheme byId(String id) {
        for (SessionJournalPageScheme scheme : all()) {
            if (scheme.id().equals(id)) {
                return scheme;
            }
        }
        return AUTO;
    }

    /** Translated for the derived schemes; the fixed palettes carry their own literal names. */
    public static String displayName(SessionJournalPageScheme scheme) {
        if (scheme == null) {
            return "";
        }
        if (scheme.name() != null) {
            return scheme.name();
        }
        String key = "journal.scheme." + scheme.id();
        String translated = I18n.get(key);
        return translated != null && !translated.equals(key) ? translated : scheme.id();
    }

    /**
     * The {@code theme} scheme filled in from the active terminal theme, so the journal page can
     * follow the colours the terminal already uses. Returns {@code auto} when no theme is known.
     */
    public static SessionJournalPageScheme resolve(String id, de.kortty.KorTTYApplication app) {
        SessionJournalPageScheme scheme = byId(id);
        if (!SessionJournalPageScheme.ID_THEME.equals(scheme.id())) {
            return scheme;
        }
        ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(app);
        if (colors == null || colors.backgroundColor() == null || colors.foregroundColor() == null) {
            return AUTO;
        }
        boolean dark = isDark(colors.backgroundColor());
        return new SessionJournalPageScheme(SessionJournalPageScheme.ID_THEME, null, true,
            colors.backgroundColor(),
            shift(colors.backgroundColor(), dark ? 0.06 : -0.02),
            shift(colors.backgroundColor(), dark ? 0.11 : -0.05),
            shift(colors.backgroundColor(), dark ? 0.20 : -0.12),
            colors.foregroundColor(),
            blend(colors.foregroundColor(), colors.backgroundColor(), 0.42),
            dark ? "#58a6ff" : "#0969da",
            dark ? "#7ee787" : "#116329",
            dark ? "#9ecbff" : "#0a3069",
            "#f2cc60", "#ff9f43");
    }

    private static boolean isDark(String hex) {
        int[] rgb = rgb(hex);
        if (rgb == null) {
            return true;
        }
        return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255.0 < 0.5;
    }

    /** Lightens (positive) or darkens (negative) a colour by a fraction of the full range. */
    private static String shift(String hex, double amount) {
        int[] rgb = rgb(hex);
        if (rgb == null) {
            return hex;
        }
        for (int i = 0; i < 3; i++) {
            rgb[i] = clamp((int) Math.round(rgb[i] + 255 * amount));
        }
        return toHex(rgb);
    }

    /** Mixes {@code a} towards {@code b}; used for the muted text colour. */
    private static String blend(String a, String b, double ratio) {
        int[] first = rgb(a);
        int[] second = rgb(b);
        if (first == null || second == null) {
            return a;
        }
        int[] mixed = new int[3];
        for (int i = 0; i < 3; i++) {
            mixed[i] = clamp((int) Math.round(first[i] * (1 - ratio) + second[i] * ratio));
        }
        return toHex(mixed);
    }

    private static int[] rgb(String hex) {
        if (hex == null) {
            return null;
        }
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() == 3) {
            value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1)
                + value.charAt(2) + value.charAt(2);
        }
        if (value.length() != 6) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(value, 255));
    }

    private static String toHex(int[] rgb) {
        return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }
}
