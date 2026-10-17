package de.kortty.ui;

import de.kortty.core.TranslationServiceFactory;
import de.kortty.model.AiProfile;
import de.kortty.model.TranslationApiProvider;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared bits of the translation UI: provider labels, locale lists, duration text, problem text. */
final class TranslationUiSupport {

    private TranslationUiSupport() { }

    static String providerLabel(TranslationApiProvider provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider) {
            case GOOGLE_TRANSLATE -> I18n.get("settings.translation.provider.google");
            case DEEPL -> I18n.get("settings.translation.provider.deepl");
            case LIBRETRANSLATE -> I18n.get("settings.translation.provider.libretranslate");
            case MICROSOFT -> I18n.get("settings.translation.provider.microsoft");
            case YANDEX -> I18n.get("settings.translation.provider.yandex");
            case LOCAL_AI_PROFILE -> I18n.get("settings.translation.provider.localAi");
        };
    }

    static StringConverter<TranslationApiProvider> providerConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(TranslationApiProvider provider) {
                return providerLabel(provider);
            }

            @Override
            public TranslationApiProvider fromString(String text) {
                return null;
            }
        };
    }

    /** "Name  (mode)" for an AI profile; the default label for null. */
    static StringConverter<AiProfile> aiProfileConverter(String defaultLabelKey) {
        return new StringConverter<>() {
            @Override
            public String toString(AiProfile profile) {
                return profile == null ? I18n.get(defaultLabelKey)
                    : profile.getName() + "  (" + profile.getConnectionMode() + ")";
            }

            @Override
            public AiProfile fromString(String value) {
                return null;
            }
        };
    }

    static StringConverter<Locale> localeConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Locale locale) {
                return locale == null ? "" : displayName(locale) + " (" + locale.getLanguage() + ")";
            }

            @Override
            public Locale fromString(String text) {
                return null;
            }
        };
    }

    /** Every language a UI bundle or guide can be generated for, one locale per language code, sorted by name. */
    static List<Locale> targetLocales() {
        Map<String, Locale> byLanguage = new LinkedHashMap<>();
        for (String code : Locale.getISOLanguages()) {
            if (code != null && !code.isBlank()) {
                byLanguage.putIfAbsent(code, Locale.forLanguageTag(code));
            }
        }
        for (Locale locale : Locale.getAvailableLocales()) {
            if (locale.getLanguage() != null && !locale.getLanguage().isBlank()) {
                byLanguage.putIfAbsent(locale.getLanguage(), locale);
            }
        }
        return byLanguage.values().stream()
            .sorted((a, b) -> displayName(a).compareToIgnoreCase(displayName(b)))
            .collect(Collectors.toList());
    }

    static String displayName(Locale locale) {
        if (locale == null) {
            return "";
        }
        String display = locale.getDisplayLanguage();
        if (display == null || display.isBlank()) {
            display = locale.getDisplayLanguage(Locale.ENGLISH);
        }
        return display == null || display.isBlank() ? locale.getLanguage() : display;
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 90) {
            return seconds + " s";
        }
        long minutes = (seconds + 30) / 60;
        if (minutes < 90) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return remainder == 0 ? hours + " h" : hours + " h " + remainder + " min";
    }

    /** The user-facing text for a factory problem. */
    static String problemText(TranslationServiceFactory.Problem problem) {
        if (problem == null) {
            return null;
        }
        return switch (problem) {
            case NO_PROFILE -> I18n.get("settings.translation.profiles.error.none");
            case NO_KEY -> I18n.get("settings.translation.error.noKey");
            case NO_AI_PROFILE -> I18n.get("settings.translation.error.noTextProfile");
            case AI_PROFILE_MISSING -> I18n.get("settings.translation.profiles.error.aiProfileMissing");
            case AI_PROFILE_NOT_LOCAL -> I18n.get("settings.translation.profiles.error.aiProfileNotLocal");
            case AI_PROFILE_UNUSABLE -> I18n.get("settings.translation.guide.error.noLocalProfile");
        };
    }
}
