package de.kortty.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared language helpers for AI-related UI selectors and fallback rules.
 */
public final class AiLanguageSupport {

    private AiLanguageSupport() {
    }

    public record LanguageOption(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static List<LanguageOption> buildAvailableLanguageOptions(String preferredCode) {
        Map<String, LanguageOption> optionsByCode = new LinkedHashMap<>();
        for (Locale locale : LanguageManager.getSupportedLocales()) {
            addLanguageOption(optionsByCode, locale);
        }
        for (Locale locale : LanguageManager.getAvailableDynamicLocales()) {
            addLanguageOption(optionsByCode, locale);
        }
        addLanguageOption(optionsByCode, LanguageManager.getInstance().getCurrentLocale());
        if (preferredCode != null && !preferredCode.isBlank()) {
            addLanguageOption(optionsByCode, Locale.forLanguageTag(preferredCode.trim()));
        }
        return new ArrayList<>(optionsByCode.values());
    }

    public static String resolveFallbackLanguageCode(String configuredLanguageCode) {
        if (configuredLanguageCode != null && !configuredLanguageCode.isBlank()) {
            return configuredLanguageCode.trim();
        }
        String guiLanguageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        return guiLanguageCode != null && !guiLanguageCode.isBlank() ? guiLanguageCode.trim() : "en";
    }

    public static LanguageOption findOption(List<LanguageOption> options, String preferredCode) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (preferredCode != null && !preferredCode.isBlank()) {
            for (LanguageOption option : options) {
                if (option != null && preferredCode.equalsIgnoreCase(option.code())) {
                    return option;
                }
            }
            return new LanguageOption(preferredCode.trim(), preferredCode.trim());
        }
        return options.getFirst();
    }

    public static String buildLanguageLabel(Locale locale) {
        String code = locale != null && locale.getLanguage() != null && !locale.getLanguage().isBlank()
            ? locale.getLanguage().trim()
            : "en";
        String displayName = locale != null ? LanguageManager.getLocaleDisplayName(locale) : null;
        if (displayName == null || displayName.isBlank()) {
            displayName = locale != null ? locale.getDisplayLanguage(Locale.ENGLISH) : code;
        }
        if (displayName == null || displayName.isBlank()) {
            return code;
        }
        return displayName + " (" + code + ")";
    }

    private static void addLanguageOption(Map<String, LanguageOption> optionsByCode, Locale locale) {
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isBlank()) {
            return;
        }
        String code = locale.getLanguage().trim();
        optionsByCode.putIfAbsent(code, new LanguageOption(code, buildLanguageLabel(locale)));
    }
}
