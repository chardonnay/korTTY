package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetEditorProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Built-in and user-defined snippet editor color profiles.
 */
public final class SnippetEditorProfileSupport {

    public static final String CURRENT_SETTINGS_PROFILE_ID = "current-settings";
    private static final String DEFAULT_CURSOR_STYLE = "BLOCK";
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private static final List<SnippetEditorProfile> BUILT_IN_PROFILES = List.of(
        profile("preset-intellij-light", "IntelliJ Light", true,
            "#080808", "#FFFFFF", "LINE", "#000000", "#808080", "#008000", "#0000FF", "#000080", "#660E7A", "#000080", "#0033B3", "#660E7A", "#000000"),
        profile("preset-darcula", "Darcula", true,
            "#A9B7C6", "#2B2B2B", "BLOCK", "#BBBBBB", "#808080", "#6A8759", "#6897BB", "#CC7832", "#9876AA", "#CC7832", "#FFC66D", "#9876AA", "#A9B7C6"),
        profile("preset-high-contrast", "High Contrast", true,
            "#FFFFFF", "#000000", "BLOCK", "#FFFF00", "#A8A8A8", "#7CFF7C", "#81D4FA", "#FFB74D", "#FF80AB", "#82B1FF", "#FFFF00", "#FF80AB", "#FFFFFF"),
        profile("preset-new-ui-dark", "New UI Dark", true,
            "#CED0D6", "#1E1F22", "LINE", "#CED0D6", "#7A7E85", "#6AAB73", "#2AACB8", "#CF8E6D", "#C77DBB", "#CF8E6D", "#56A8F5", "#C77DBB", "#CED0D6"),
        profile("preset-warm-light", "Warm Light", true,
            "#1F2328", "#FAFAF7", "LINE", "#1F2328", "#7C7C78", "#067D17", "#1750EB", "#0033B3", "#871094", "#000080", "#7A3E9D", "#871094", "#1F2328"),
        profile("preset-blue-light", "Blue Light", true,
            "#1B1F2A", "#F4F8FF", "LINE", "#1B1F2A", "#6E7B8B", "#067D17", "#1D65C1", "#003B8E", "#8A1C7C", "#003B8E", "#265D9E", "#8A1C7C", "#1B1F2A"),
        profile("preset-graphite-dark", "Graphite Dark", true,
            "#D4D4D4", "#252526", "BLOCK", "#D4D4D4", "#858585", "#CE9178", "#B5CEA8", "#4EC9B0", "#9CDCFE", "#569CD6", "#C586C0", "#9CDCFE", "#D4D4D4"),
        profile("preset-night-owl", "Night Owl", true,
            "#D6DEEB", "#011627", "BLOCK", "#80CBC4", "#637777", "#ECC48D", "#F78C6C", "#FFCB8B", "#C792EA", "#82AAFF", "#7FDBCA", "#C792EA", "#D6DEEB"),
        profile("preset-solarized-light", "Solarized Light", true,
            "#586E75", "#FDF6E3", "LINE", "#586E75", "#93A1A1", "#2AA198", "#268BD2", "#B58900", "#D33682", "#859900", "#6C71C4", "#D33682", "#586E75"),
        profile("preset-solarized-dark", "Solarized Dark", true,
            "#839496", "#002B36", "BLOCK", "#93A1A1", "#586E75", "#2AA198", "#268BD2", "#B58900", "#D33682", "#859900", "#6C71C4", "#D33682", "#839496")
    );

    private SnippetEditorProfileSupport() {
    }

    public static List<SnippetEditorProfile> builtInProfiles() {
        return BUILT_IN_PROFILES.stream()
            .map(SnippetEditorProfile::new)
            .toList();
    }

    public static Optional<SnippetEditorProfile> builtInProfile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return BUILT_IN_PROFILES.stream()
            .filter(profile -> id.equals(profile.getId()))
            .findFirst()
            .map(SnippetEditorProfile::new);
    }

    public static List<SnippetEditorProfile> customProfiles(GlobalSettings settings) {
        if (settings == null || settings.getSnippetEditorProfiles() == null) {
            return List.of();
        }
        return settings.getSnippetEditorProfiles().stream()
            .filter(profile -> profile != null && !profile.isBuiltIn())
            .map(SnippetEditorProfileSupport::normalize)
            .toList();
    }

    public static List<SnippetEditorProfile> allProfiles(GlobalSettings settings) {
        List<SnippetEditorProfile> profiles = new ArrayList<>(customProfiles(settings));
        profiles.addAll(builtInProfiles());
        return profiles;
    }

    public static SnippetEditorProfile resolveActiveProfile(GlobalSettings settings, SnippetEditorProfile fallback) {
        String selectedId = settings != null ? settings.getSelectedSnippetEditorProfileId() : null;
        if (selectedId != null && !selectedId.isBlank()) {
            Optional<SnippetEditorProfile> selected = allProfiles(settings).stream()
                .filter(profile -> selectedId.equals(profile.getId()))
                .findFirst();
            if (selected.isPresent()) {
                return normalize(selected.get());
            }
        }
        return normalize(fallback);
    }

    public static SnippetEditorProfile fromCurrentSettings(
        String foregroundColor,
        String backgroundColor,
        String cursorStyle,
        String cursorColor) {

        SnippetEditorProfile profile = new SnippetEditorProfile();
        profile.setId(CURRENT_SETTINGS_PROFILE_ID);
        profile.setName("Custom colors");
        profile.setBuiltIn(false);
        profile.setForegroundColor(foregroundColor);
        profile.setBackgroundColor(backgroundColor);
        profile.setCursorStyle(cursorStyle);
        profile.setCursorColor(cursorColor);
        profile.setCommentColor("#888888");
        profile.setStringColor("#008800");
        profile.setNumberColor("#0066CC");
        profile.setBooleanColor("#CC00CC");
        profile.setKeyColor("#CC0000");
        profile.setKeywordColor("#0000CC");
        profile.setSectionColor("#9900CC");
        profile.setVariableColor("#CC6600");
        profile.setBraceColor("#CC6600");
        return normalize(profile);
    }

    public static SnippetEditorProfile normalize(SnippetEditorProfile source) {
        SnippetEditorProfile fallback = BUILT_IN_PROFILES.get(1);
        SnippetEditorProfile profile = source != null ? new SnippetEditorProfile(source) : new SnippetEditorProfile(fallback);
        profile.setId(nonBlank(profile.getId(), UUID.randomUUID().toString()));
        profile.setName(nonBlank(profile.getName(), "Snippet editor profile"));
        profile.setForegroundColor(hex(profile.getForegroundColor(), fallback.getForegroundColor()));
        profile.setBackgroundColor(hex(profile.getBackgroundColor(), fallback.getBackgroundColor()));
        profile.setCursorStyle(cursorStyle(profile.getCursorStyle()));
        profile.setCursorColor(hex(profile.getCursorColor(), fallback.getCursorColor()));
        profile.setCommentColor(hex(profile.getCommentColor(), fallback.getCommentColor()));
        profile.setStringColor(hex(profile.getStringColor(), fallback.getStringColor()));
        profile.setNumberColor(hex(profile.getNumberColor(), fallback.getNumberColor()));
        profile.setBooleanColor(hex(profile.getBooleanColor(), fallback.getBooleanColor()));
        profile.setKeyColor(hex(profile.getKeyColor(), fallback.getKeyColor()));
        profile.setKeywordColor(hex(profile.getKeywordColor(), fallback.getKeywordColor()));
        profile.setSectionColor(hex(profile.getSectionColor(), fallback.getSectionColor()));
        profile.setVariableColor(hex(profile.getVariableColor(), fallback.getVariableColor()));
        profile.setBraceColor(hex(profile.getBraceColor(), fallback.getBraceColor()));
        return profile;
    }

    public static String hex(String value, String fallback) {
        if (value != null && HEX_COLOR.matcher(value.trim()).matches()) {
            return value.trim().toUpperCase(Locale.ROOT);
        }
        if (fallback != null && HEX_COLOR.matcher(fallback.trim()).matches()) {
            return fallback.trim().toUpperCase(Locale.ROOT);
        }
        return "#000000";
    }

    public static String cursorStyle(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CURSOR_STYLE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LINE", "UNDERSCORE", "BLOCK" -> normalized;
            default -> DEFAULT_CURSOR_STYLE;
        };
    }

    private static SnippetEditorProfile profile(
        String id,
        String name,
        boolean builtIn,
        String foreground,
        String background,
        String cursorStyle,
        String cursorColor,
        String comment,
        String string,
        String number,
        String bool,
        String key,
        String keyword,
        String section,
        String variable,
        String brace) {

        SnippetEditorProfile profile = new SnippetEditorProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setBuiltIn(builtIn);
        profile.setForegroundColor(foreground);
        profile.setBackgroundColor(background);
        profile.setCursorStyle(cursorStyle);
        profile.setCursorColor(cursorColor);
        profile.setCommentColor(comment);
        profile.setStringColor(string);
        profile.setNumberColor(number);
        profile.setBooleanColor(bool);
        profile.setKeyColor(key);
        profile.setKeywordColor(keyword);
        profile.setSectionColor(section);
        profile.setVariableColor(variable);
        profile.setBraceColor(brace);
        return profile;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
